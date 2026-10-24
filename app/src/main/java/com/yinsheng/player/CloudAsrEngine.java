package com.yinsheng.player;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 联网 ASR 引擎（云端识别，按音频时长计费）。
 *
 * 统一能力：识别一段 16k mono 16bit WAV（≤60 秒，对应一个字幕分片），返回带时间戳的句子列表。
 * 三家厂商（设置页选择）：
 *  - 腾讯云：录音文件识别 CreateRecTask（SourceType=1 本地 base64）+ DescribeTaskStatus 轮询（TC3-HMAC-SHA256 签名）
 *  - 阿里云：百炼 qwen3-asr-flash（同步多模态接口，本地 base64 data URI 上传，≤10MB；Bearer 百炼 API Key，与翻译共用）
 *  - 讯飞：语音听写 iat（WebSocket 流式，支持日语；APPID + APIKey + APISecret 签名）
 *
 * 注意：音频会上传到所选厂商服务器（与离线引擎本质不同），涉及隐私与计费，由设置页开关控制。
 * 云端返回字段若与文档有出入，会在错误信息中给出原始片段，便于修正。
 */
public class CloudAsrEngine {

    private static final String TAG = "CloudAsrEngine";

    /** 一句识别结果（相对传入音频起点的毫秒时间轴） */
    public static class Sentence {
        public long startMs, endMs;
        public String text;
        Sentence(long s, long e, String t) { startMs = s; endMs = e; text = t; }
    }

    /** 识别一段 WAV 的通用接口（实现为阻塞调用，必须在后台线程执行） */
    public interface Provider {
        List<Sentence> recognize(byte[] wav16k) throws Exception;
        String name();
    }

    /** 按设置创建对应厂商引擎（未配置密钥时抛异常，消息说明缺什么） */
    public static Provider create(AppSettings s) throws Exception {
        switch (s.getCloudAsrEngine()) {
            case "alibaba":
                return new AlibabaProvider(s);
            case "iflytek":
                return new IflytekProvider(s);
            case "tencent":
            default:
                return new TencentProvider(s);
        }
    }

    // ============================================================
    // 腾讯云 录音文件识别（TC3-HMAC-SHA256 签名）
    // ============================================================
    static class TencentProvider implements Provider {
        private final String secretId, secretKey;
        private static final String HOST = "asr.tencentcloudapi.com";
        private static final String SERVICE = "asr";
        private static final String REGION = "ap-guangzhou";
        TencentProvider(AppSettings s) throws Exception {
            secretId = s.getTencentId().trim();
            secretKey = s.getTencentKey().trim();
            if (secretId.isEmpty() || secretKey.isEmpty()) throw new Exception("请先填写腾讯云 SecretId 与 SecretKey");
        }
        public String name() { return "腾讯云"; }

        @Override
        public List<Sentence> recognize(byte[] wav16k) throws Exception {
            long taskId = submit(wav16k);
            // 轮询任务状态（最长 60 秒）
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1200);
                JSONObject resp = call("DescribeTaskStatus", new JSONObject().put("TaskId", taskId));
                JSONObject data = resp.optJSONObject("Response");
                if (data == null) throw new Exception("腾讯云返回异常: " + resp);
                int status = data.optInt("Status", 0);
                if (status == 2) {
                    String err = data.optString("ErrorMsg");
                    if (!err.isEmpty()) throw new Exception("腾讯云识别失败: " + err);
                    // 大模型 2.0：Result 为字符串化的 JSON，含 AudioData 数组（带时间戳句子）
                    String resultStr = data.optString("Result");
                    return parseResult(resultStr);
                }
                if (status == 1) return new ArrayList<>();   // 无语音
            }
            throw new Exception("腾讯云识别超时");
        }

        private long submit(byte[] wav16k) throws Exception {
            String b64 = Base64.encodeToString(wav16k, Base64.NO_WRAP);
            JSONObject payload = new JSONObject()
                    .put("EngineModelType", "16k_zh_ja")   // 大模型 2.0 中日文混说
                    .put("ChannelNum", 1)
                    .put("ResTextFormat", 0)
                    .put("SourceType", 1)                  // 本地上传
                    .put("Data", b64)
                    .put("ConvertNumMode", 1);
            JSONObject resp = call("CreateRecTask", payload);
            JSONObject data = resp.optJSONObject("Response");
            if (data == null || !data.has("Data")) throw new Exception("腾讯云提交失败: " + resp);
            long taskId = data.optJSONObject("Data").optLong("TaskId");
            if (taskId == 0) throw new Exception("腾讯云 TaskId 为空: " + resp);
            return taskId;
        }

        private List<Sentence> parseResult(String resultStr) {
            List<Sentence> out = new ArrayList<>();
            try {
                JSONObject r = new JSONObject(resultStr);
                JSONArray audio = r.optJSONArray("AudioData");
                if (audio != null) {
                    for (int i = 0; i < audio.length(); i++) {
                        JSONObject item = audio.getJSONObject(i);
                        String text = item.optString("SentenceText");
                        if (text.isEmpty()) text = item.optString("Text");
                        long s = item.optLong("StartMs", item.optLong("StartTime", 0));
                        long e = item.optLong("EndMs", item.optLong("EndTime", s));
                        if (!text.isEmpty()) out.add(new Sentence(s, e, text.trim()));
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "腾讯云 Result 解析失败: " + resultStr, ex);
            }
            return out;
        }

        /** TC3-HMAC-SHA256 签名 + POST JSON */
        private JSONObject call(String action, JSONObject payload) throws Exception {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(Long.parseLong(timestamp) * 1000));
            String body = payload.toString();
            String ct = "application/json; charset=utf-8";

            String canonicalHeaders = "content-type:" + ct + "\nhost:" + HOST + "\n";
            String signedHeaders = "content-type;host";
            String hashedPayload = sha256Hex(body);
            String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedPayload;

            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

            byte[] secretDate = hmac("TC3" + secretKey, date);
            byte[] secretService = hmac(secretDate, SERVICE);
            byte[] secretSigning = hmac(secretService, "tc3_request");
            String signature = hex(hmac(secretSigning, stringToSign));

            String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpURLConnection conn = (HttpURLConnection) new URL("https://" + HOST + "/").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Host", HOST);
            conn.setRequestProperty("Content-Type", ct);
            conn.setRequestProperty("X-TC-Action", action);
            conn.setRequestProperty("X-TC-Version", "2019-06-14");
            conn.setRequestProperty("X-TC-Timestamp", timestamp);
            conn.setRequestProperty("X-TC-Region", REGION);
            conn.setRequestProperty("Authorization", authorization);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            InputStream in;
            try { in = conn.getInputStream(); }
            catch (Exception e) { in = conn.getErrorStream(); }
            String resp = readAll(in == null ? new java.io.ByteArrayInputStream(new byte[0]) : in);
            return new JSONObject(resp);
        }
    }

    // ============================================================
    // 阿里云 百炼 Fun-ASR-Realtime（WebSocket 流式，句子级时间戳）
    //
    // 选型说明：qwen3-asr-flash 的 OpenAI 兼容同步接口只返回整段文本、无句子时间戳
    //   （这就是"一分钟一条大段字幕"的原因）；Fun-ASR-Realtime 为 WebSocket 流式接口，
    //   支持本地 PCM 直传、返回带 begin_time/end_time 的句子级结果（sentence_end 事件），
    //   支持日语，免费额度 36,000 秒（10 小时），计费约 0.00033 元/秒 ≈ 1.2 元/小时。
    //   协议：wss://dashscope.aliyuncs.com/api-ws/v1/inference，Authorization: bearer <API Key>
    //   流程：run-task → task-started → 二进制 PCM 流 → finish-task → task-finished。
    //   鉴权仅需一把百炼 API Key（与翻译兜底共用）。
    // ============================================================
    static class AlibabaProvider implements Provider {
        private final String apiKey;
        private static final String WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
        AlibabaProvider(AppSettings s) throws Exception {
            apiKey = s.getAlibabaKey().trim();   // 百炼 API Key（与翻译兜底共用同一把）
            if (apiKey.isEmpty()) throw new Exception("请先填写阿里云百炼 API Key（设置 → 联网 ASR → 阿里云）");
        }
        public String name() { return "阿里云(百炼)"; }

        @Override
        public List<Sentence> recognize(byte[] wav16k) throws Exception {
            // 1) 剥掉 44 字节 WAV 头，拿纯 PCM（16kHz mono 16bit）流式直传
            final byte[] pcm;
            if (wav16k.length > 44 && wav16k[0] == 'R' && wav16k[1] == 'I'
                    && wav16k[2] == 'F' && wav16k[3] == 'F') {
                pcm = new byte[wav16k.length - 44];
                System.arraycopy(wav16k, 44, pcm, 0, pcm.length);
            } else {
                pcm = wav16k;
            }
            final String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            final List<Sentence> out = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] err = new Throwable[1];

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder().url(WS_URL)
                    .header("Authorization", "bearer " + apiKey)
                    .build();
            WebSocket ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    try {
                        JSONObject header = new JSONObject()
                                .put("action", "run-task")
                                .put("task_id", taskId)
                                .put("streaming", "duplex");
                        JSONObject payload = new JSONObject()
                                .put("task_group", "audio")
                                .put("task", "asr")
                                .put("function", "recognition")
                                .put("model", "fun-asr-realtime")
                                .put("parameters", new JSONObject()
                                        .put("format", "pcm")
                                        .put("sample_rate", 16000))
                                .put("input", new JSONObject());
                        webSocket.send(new JSONObject().put("header", header).put("payload", payload).toString());
                    } catch (Exception e) {
                        err[0] = e; latch.countDown();
                    }
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject o = new JSONObject(text);
                        JSONObject header = o.optJSONObject("header");
                        String event = header != null ? header.optString("event", "") : "";
                        if ("task-started".equals(event)) {
                            // 收到 task-started 后才能开始发音频；单独线程快速推送（本地文件，非实时）
                            new Thread(() -> sendAudio(webSocket)).start();
                        } else if ("result-generated".equals(event)) {
                            JSONObject payload = o.optJSONObject("payload");
                            JSONObject output = payload != null ? payload.optJSONObject("output") : null;
                            JSONObject sentence = output != null ? output.optJSONObject("sentence") : null;
                            if (sentence != null && !sentence.optBoolean("heartbeat", false)
                                    && sentence.optBoolean("sentence_end", false)) {
                                String t = sentence.optString("text", "").trim();
                                long begin = sentence.optLong("begin_time", -1);
                                long end = sentence.optLong("end_time", -1);
                                if (!t.isEmpty() && begin >= 0 && end >= 0) {
                                    out.add(new Sentence(begin, end, t));
                                    sb.append(t);
                                }
                            }
                        } else if ("task-finished".equals(event)) {
                            latch.countDown();
                        } else if ("task-failed".equals(event)) {
                            String msg = header != null ? header.optString("error_message", "识别失败") : "识别失败";
                            err[0] = new Exception(msg);
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        err[0] = e; latch.countDown();
                    }
                }

                private void sendAudio(WebSocket ws) {
                    try {
                        int chunk = 3200;   // 100ms @16kHz mono 16bit
                        int i = 0;
                        while (i < pcm.length) {
                            int len = Math.min(chunk, pcm.length - i);
                            ws.send(okio.ByteString.of(pcm, i, len));
                            i += len;
                            // 本地文件发送：每块 5ms ≈ 20×实时（60 秒分片约 3 秒发完）。
                            // 生成速度决定"边播边生成"能否追上播放：降速到 25ms（4×实时）实测
                            // 每分片生成 ~40s+，播放到下一分片时字幕还没好（"1:07 字幕跟不上"）。
                            // 服务端按数据量计时间戳（已验证），快速发送不丢帧。
                            try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                        }
                        // 音频发完 → finish-task，服务端处理剩余音频后回 task-finished
                        JSONObject header = new JSONObject()
                                .put("action", "finish-task")
                                .put("task_id", taskId)
                                .put("streaming", "duplex");
                        ws.send(new JSONObject().put("header", header)
                                .put("payload", new JSONObject().put("input", new JSONObject())).toString());
                    } catch (Exception e) {
                        err[0] = e; latch.countDown();
                    }
                }

                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    err[0] = t; latch.countDown();
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    latch.countDown();
                }
            });

            boolean done = latch.await(90, TimeUnit.SECONDS);
            if (!done) { ws.close(1000, "timeout"); throw new Exception("阿里云百炼识别超时"); }
            if (err[0] != null) throw new Exception("阿里云百炼识别失败: " + err[0].getMessage());
            // 有文本但无句子级结果（极端情况）→ 整段兜底；无文本 → 无语音分片（ASMR 静音段）
            if (out.isEmpty() && !sb.toString().trim().isEmpty()) {
                out.add(new Sentence(0, wavDurationMs(wav16k), sb.toString().trim()));
            }
            return out;
        }

        /** 解析 WAV 头 data 块大小算音频时长（16kHz mono 16bit → ms）；失败按数据长度估算 */
        private long wavDurationMs(byte[] wav) {
            try {
                long dataSize = 0;
                for (int i = 3; i >= 0; i--) dataSize = (dataSize << 8) | (wav[40 + i] & 0xFF);
                if (dataSize > 0) return dataSize / 2 / 16;
            } catch (Exception ignored) {}
            return Math.max(0, (wav.length - 44L)) * 1000L / 2 / 16;
        }
    }

    // ============================================================
    // 讯飞 语音听写 iat（WebSocket 流式，支持日语）
    // ============================================================
    static class IflytekProvider implements Provider {
        private final String appId, apiKey, apiSecret;
        private static final String WS_URL = "wss://iat-api.xfyun.cn/v2/iat";
        IflytekProvider(AppSettings s) throws Exception {
            appId = s.getIflytekAppId().trim();
            apiKey = s.getIflytekApiKey().trim();
            apiSecret = s.getIflytekApiSecret().trim();
            if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty())
                throw new Exception("请先填写讯飞 APPID / APIKey / APISecret");
        }
        public String name() { return "讯飞"; }

        @Override
        public List<Sentence> recognize(byte[] wav16k) throws Exception {
            // 1) 构造带签名的 WebSocket URL
            String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US) {{
                setTimeZone(TimeZone.getTimeZone("GMT"));
            }}.format(new Date());
            String host = "iat-api.xfyun.cn";
            String origin = "GET " + "/v2/iat" + " HTTP/1.1";
            String signStr = "host: " + host + "\ndate: " + date + "\n" + origin;
            String signature = base64(hmac(apiSecret, signStr));
            String authorization = base64((apiKey + ":" + signature).getBytes(StandardCharsets.UTF_8));
            String url = WS_URL + "?authorization=" + urlEncode(authorization)
                    + "&date=" + urlEncode(date) + "&host=" + host;

            final List<Sentence> out = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] err = new Throwable[1];

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder().url(url).build();
            WebSocket ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    // 发送开始帧
                    try {
                        JSONObject frame = new JSONObject();
                        frame.put("common", new JSONObject().put("app_id", appId));
                        JSONObject business = new JSONObject()
                                .put("language", "zh_cn")      // 支持 ja_jp；默认中文（讯飞识别日语需选 ja_jp）
                                .put("domain", "iat")
                                .put("accent", "mandarin")
                                .put("vad_eos", 10000)
                                .put("punctuation", 1)
                                .put("dwa", "wpgs");           // 返回带时间戳的句子（wpgs 需要）
                        frame.put("business", business);
                        frame.put("data", new JSONObject()
                                .put("status", 0)
                                .put("format", "audio/L16;rate=16000")
                                .put("encoding", "raw")
                                .put("audio", ""));
                        webSocket.send(frame.toString());
                        // 发送音频帧（每次 1280 字节 = 40ms）；20ms 间隔让服务端来得及消费
                        int i = 0;
                        while (i < wav16k.length) {
                            int len = Math.min(1280, wav16k.length - i);
                            byte[] chunk = new byte[len];
                            System.arraycopy(wav16k, i, chunk, 0, len);
                            webSocket.send(okio.ByteString.of(chunk));
                            i += len;
                            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                        }
                        // 结束帧
                        webSocket.send(new JSONObject().put("data", new JSONObject()
                                .put("status", 2).put("format", "audio/L16;rate=16000")
                                .put("encoding", "raw").put("audio", "")).toString());
                    } catch (Exception e) {
                        err[0] = e; latch.countDown();
                    }
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject o = new JSONObject(text);
                        int code = o.optInt("code", 0);
                        if (code != 0) {
                            err[0] = new Exception("讯飞返回错误 " + code + ": " + o.optString("message"));
                            latch.countDown();
                            return;
                        }
                        JSONObject data = o.optJSONObject("data");
                        if (data == null) return;
                        JSONObject result = data.optJSONObject("result");
                        if (result != null) {
                            JSONArray wsArr = result.optJSONArray("ws");
                            if (wsArr != null) {
                                StringBuilder seg = new StringBuilder();
                                for (int i = 0; i < wsArr.length(); i++) {
                                    JSONArray cw = wsArr.getJSONObject(i).optJSONArray("cw");
                                    if (cw != null && cw.length() > 0) seg.append(cw.getJSONObject(0).optString("w"));
                                }
                                // wpgs 模式下每条消息带 begin/end 时间戳
                                long begin = result.optLong("begin", -1), end = result.optLong("end", -1);
                                String t = seg.toString().trim();
                                if (!t.isEmpty()) {
                                    sb.append(t);
                                    if (begin >= 0 && end >= 0) out.add(new Sentence(begin, end, t));
                                }
                            }
                        }
                        if (data.optInt("status", 0) == 2) latch.countDown();
                    } catch (Exception e) {
                        err[0] = e; latch.countDown();
                    }
                }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    err[0] = t; latch.countDown();
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    latch.countDown();
                }
            });

            boolean done = latch.await(90, TimeUnit.SECONDS);
            if (!done) { ws.close(1000, "timeout"); throw new Exception("讯飞识别超时"); }
            if (err[0] != null) throw new Exception("讯飞识别失败: " + err[0].getMessage());
            if (out.isEmpty() && !sb.toString().trim().isEmpty()) {
                out.add(new Sentence(0, (long) (wav16k.length / 2 / 16.0), sb.toString().trim()));
            }
            return out;
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }

    private static String base64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String urlEncode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
}
