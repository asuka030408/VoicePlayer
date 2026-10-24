package com.yinsheng.player;

import android.content.Context;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 翻译层接口。
 *
 * 【当前实现】
 *  - 百度翻译开放平台（国内直连，无需 VPN）：免费版每月 5 万字符额度，逐句翻译；
 *  - DeepSeek LLM（国内直连，需 API Key，按 token 计费极低）：批量翻译一个分片的全部句子，
 *    一次请求出所有译文——比逐句更省 token、且句子之间有上下文，译文更连贯。
 *
 * Factory.create() 按设置页选择的引擎创建；未配置 → 返回 null，
 * 字幕管线自动跳过翻译，只显示本地识别原文（不浪费任何请求）。
 *
 * 【预留替换接口】后续若要换其他翻译服务（如 Marian-NMT 开源模型）：
 *   1. 新增一个 XxxTranslationEngine implements TranslationEngine；
 *   2. 在 Factory.create() 中替换实现；
 *   3. 字幕管线（SubtitlePipeline）无需任何改动。
 *
 * 安全约定：只把「识别出的文字」发给翻译接口，音频数据绝不离开本机。
 */
public interface TranslationEngine {

    String name();

    /** 当前是否可用（额度用尽/账号错误后自动禁用） */
    boolean isAvailable();

    /** 阻塞翻译（必须在后台线程调用），失败抛异常由调用方处理 */
    String translate(String text) throws Exception;

    /**
     * 批量翻译（阻塞，后台线程）。默认实现逐句调用 translate()（百度引擎用）。
     * 支持批量的引擎（如 DeepSeek）重写此方法，一次请求翻译多句、省 token 且更连贯。
     * 返回的列表与输入一一对应；解析失败返回 null 时，调用方逐句 translate() 兜底。
     */
    default List<String> translateBatch(List<String> texts) throws Exception {
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(translate(t));
        return out;
    }

    /** 判断文本是否含中文字符（决定翻译方向） */
    static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // 实现：百度翻译开放平台（国内直连；需用户注册免费 APPID/密钥）
    // ---------------------------------------------------------------
    class BaiduTranslationEngine implements TranslationEngine {
        private static final String TAG = "BaiduTrans";
        private static final String API = "https://fanyi-api.baidu.com/api/trans/vip/translate";
        private final String appid;
        private final String key;
        private long lastCallMs = 0;
        /** 额度/账号类错误（54003 无额度、54004 余额不足、54000/54001 参数或签名错误等）→ 后续句子不再尝试 */
        private volatile boolean disabled;

        BaiduTranslationEngine(String appid, String key) {
            this.appid = appid == null ? "" : appid.trim();
            this.key = key == null ? "" : key.trim();
        }

        @Override public String name() { return "百度翻译(国内直连)"; }

        @Override public boolean isAvailable() { return !disabled && !appid.isEmpty() && !key.isEmpty(); }

        @Override public String translate(String text) throws Exception {
            if (!isAvailable()) throw new IllegalStateException("百度翻译不可用（未配置或已停用）");
            if (text == null || text.trim().isEmpty()) return "";
            // 免费版 QPS=1：相邻请求间隔 1.2s，避免触发限流
            long wait = lastCallMs + 1200 - System.currentTimeMillis();
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException ie) { /* 忽略 */ } }
            // 目标语言固定为中文：用户场景是看中文译文（英语/日语/韩语歌 → 中文）。
            // 注意不能用"原文含汉字则翻英文"的双向逻辑——日文歌普遍含汉字（本多、お姉さん…），
            // 会被误判为"中文原文"而把译文变成英文（真机复现：日语 ASMR 译文显示成英文）。
            // 中文为主的文本（CJK≥30%）已在上游 SubtitlePipeline.isChineseText 跳过翻译，
            // 能走到这里的都是外语，统一译为中文即可。
            String to = "zh";
            String salt = String.valueOf(System.currentTimeMillis());
            String sign = md5(appid + text + salt + key);
            String body = "q=" + java.net.URLEncoder.encode(text, "UTF-8")
                    + "&from=auto&to=" + to
                    + "&appid=" + appid
                    + "&salt=" + salt
                    + "&sign=" + sign;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                java.io.InputStream in = conn.getInputStream();
                byte[] buf = new byte[4096];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                String resp = sb.toString().trim();
                org.json.JSONObject o = new org.json.JSONObject(resp);
                if (o.has("error_code")) {
                    String ec = o.optString("error_code");
                    // 额度/账号类错误：禁用引擎，剩余句子不再发请求（缓存只留原文，下次也不重复翻译）
                    if (ec.startsWith("5400") || ec.startsWith("5401") || ec.startsWith("5403") || ec.startsWith("5404") || ec.startsWith("52001")) {
                        disabled = true;
                        Log.w(TAG, "百度翻译不可用，已停用（" + ec + " " + o.optString("error_msg") + "）");
                    }
                    throw new Exception("百度错误 " + ec + ":" + o.optString("error_msg"));
                }
                org.json.JSONArray arr = o.optJSONArray("trans_result");
                if (arr == null || arr.length() == 0) return "";
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) out.append(' ');
                    out.append(arr.optJSONObject(i).optString("dst", ""));
                }
                return out.toString().trim();
            } finally {
                lastCallMs = System.currentTimeMillis();
                if (conn != null) conn.disconnect();
            }
        }

        private static String md5(String s) throws Exception {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------
    // 实现 2：OpenAI 兼容 LLM 翻译（DeepSeek / 智谱 GLM / 阿里通义 共用一套实现）
    //   - 批量翻译：一个分片的所有待译句子一次请求返回，省 token + 上下文更连贯；
    //   - 解析失败（LLM 不按编号/行数返回）时返回 null，调用方逐句兜底；
    //   - 账号/额度类错误（401/402/429）自动禁用，由外层兜底链自动切下一个引擎。
    // ---------------------------------------------------------------
    class OpenAICompatTranslationEngine implements TranslationEngine {
        private static final String TAG = "OpenAICompatTrans";
        private static final String SYSTEM_PROMPT =
                "你是字幕翻译助手。把用户消息中的每行文本翻译成简体中文。\n" +
                "要求：逐行输出译文，每行对应一条输入；先输出行号“N.”再输出译文；不要添加任何解释、前后缀或多余内容。\n" +
                "示例：\n" +
                "1. 早上好\n" +
                "2. 今天天气真好\n";
        private final String displayName;
        private final String apiBase;    // 完整 chat/completions URL
        private final String model;
        private final String key;
        /** 账号/额度类错误后置 true：后续句子不再发请求，由外层兜底链自动切下一个引擎 */
        private volatile boolean disabled;

        OpenAICompatTranslationEngine(String displayName, String apiBase, String model, String key) {
            this.displayName = displayName;
            this.apiBase = apiBase;
            this.model = model;
            this.key = key == null ? "" : key.trim();
        }

        @Override public String name() { return displayName + "(联网)"; }

        @Override public boolean isAvailable() { return !disabled && !key.isEmpty(); }

        @Override public String translate(String text) throws Exception {
            if (!isAvailable()) throw new IllegalStateException(displayName + " 不可用（未配置 Key 或已停用）");
            if (text == null || text.trim().isEmpty()) return "";
            List<String> r = translateBatch(java.util.Collections.singletonList(text));
            return (r == null || r.isEmpty()) ? "" : r.get(0);
        }

        @Override public List<String> translateBatch(List<String> texts) throws Exception {
            if (!isAvailable()) throw new IllegalStateException(displayName + " 不可用（未配置 Key 或已停用）");
            if (texts == null || texts.isEmpty()) return new ArrayList<>();
            // 编号拼装：让 LLM 输出可按编号精确对齐
            StringBuilder user = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                user.append(i + 1).append(". ").append(texts.get(i)).append('\n');
            }
            String content = chat(user.toString());
            if (content == null) return null;

            // 解析：优先按 "N." 前缀；逐行扫描
            List<String> out = new ArrayList<>(texts.size());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:^|\\n)\\s*(\\d+)\\s*[.、]\\s*([^\\n]+)")
                    .matcher("\n" + content);
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1)) - 1;
                String tr = m.group(2).trim();
                if (idx < 0) continue;
                while (out.size() <= idx) out.add(null);
                out.set(idx, tr);
            }
            // 无编号（LLM 没按格式）：按行数拆分
            if (out.size() < texts.size()) {
                String[] lines = content.split("\\n");
                out = new ArrayList<>(texts.size());
                for (String ln : lines) {
                    String t = ln.trim();
                    // 去掉可能残留的编号前缀
                    t = t.replaceFirst("^\\d+\\s*[.、]\\s*", "");
                    if (!t.isEmpty()) out.add(t);
                    if (out.size() >= texts.size()) break;
                }
            }
            // 行数不足：视为解析失败，调用方逐句兜底
            if (out.size() < texts.size()) return null;
            // 补齐可能缺的尾部（LLM 少给几行时置空，避免错位）
            while (out.size() < texts.size()) out.add("");
            return out.subList(0, texts.size());
        }

        /** 调用 OpenAI 兼容 chat/completions，返回 choices[0].message.content；网络/服务错误抛异常 */
        private String chat(String userPrompt) throws Exception {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            org.json.JSONArray msgs = new org.json.JSONArray();
            org.json.JSONObject sys = new org.json.JSONObject();
            sys.put("role", "system");
            sys.put("content", SYSTEM_PROMPT);
            org.json.JSONObject usr = new org.json.JSONObject();
            usr.put("role", "user");
            usr.put("content", userPrompt);
            msgs.put(sys);
            msgs.put(usr);
            body.put("messages", msgs);
            body.put("temperature", 0.3);
            body.put("max_tokens", 4096);

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(apiBase).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                java.io.InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while (in != null && (n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                String resp = sb.toString().trim();
                if (code == 401 || code == 402 || code == 429) {
                    disabled = true;   // 账号/额度错误：停用该引擎，兜底链自动切下一个
                    Log.w(TAG, displayName + " 不可用，已停用（HTTP " + code + "）");
                    throw new Exception(displayName + " 错误 HTTP " + code);
                }
                if (code != 200) throw new Exception(displayName + " HTTP " + code + ": " + resp);
                org.json.JSONObject o = new org.json.JSONObject(resp);
                org.json.JSONArray choices = o.optJSONArray("choices");
                if (choices == null || choices.length() == 0) return null;
                return choices.optJSONObject(0)
                        .optJSONObject("message")
                        .optString("content", "")
                        .trim();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------
    // ---------------------------------------------------------------
    // 兜底链：一个引擎额度用尽/失效后，自动切换下一个已配置引擎
    //  ---------------------------------------------------------------
    class ChainTranslationEngine implements TranslationEngine {
        private final List<TranslationEngine> chain;

        ChainTranslationEngine(List<TranslationEngine> chain) {
            this.chain = chain;
        }

        @Override public String name() { return "翻译链（自动兜底）"; }

        @Override public boolean isAvailable() {
            for (TranslationEngine e : chain) if (e.isAvailable()) return true;
            return false;
        }

        @Override public String translate(String text) throws Exception {
            Exception last = null;
            for (TranslationEngine e : chain) {
                if (!e.isAvailable()) continue;
                try {
                    return e.translate(text);
                } catch (Exception ex) {
                    last = ex;   // 该引擎失败（额度/网络/解析），继续尝试下一个
                }
            }
            throw last != null ? last : new Exception("所有翻译引擎均不可用");
        }

        @Override public List<String> translateBatch(List<String> texts) throws Exception {
            Exception last = null;
            for (TranslationEngine e : chain) {
                if (!e.isAvailable()) continue;
                try {
                    List<String> r = e.translateBatch(texts);
                    if (r != null) return r;
                    last = new Exception(e.name() + " 批量解析失败");
                } catch (Exception ex) {
                    last = ex;
                }
            }
            throw last != null ? last : new Exception("所有翻译引擎均不可用");
        }
    }

    // ---------------------------------------------------------------
    // 工厂：创建翻译引擎（首选 + 兜底链）
    // ---------------------------------------------------------------
    class Factory {
        /**
         * 创建翻译引擎（按设置页选择的首选引擎 + 所有已配置引擎组成兜底链）：
         *  - 首选引擎排在最前（默认 DeepSeek）；
         *  - 其余已填写密钥的厂商按固定顺序排在后面：DeepSeek → 百度 → 智谱 → 阿里；
         *  - 首选引擎额度用尽/报错时，自动切下一个可用引擎，同一句只翻一次、只扣一次额度；
         *  - 一个都没配置 → 返回 null，字幕管线跳过翻译、只显示识别原文。
         */
        static TranslationEngine create(Context ctx, AppSettings settings) {
            List<TranslationEngine> chain = new ArrayList<>();

            // 按「首选 + 其他已配」排序：首选在最前
            String primary = settings.getTransEngine();
            List<String> order = new ArrayList<>();
            order.add(primary);
            for (String s : new String[]{"deepseek", "baidu", "zhipu", "alibaba"}) {
                if (!order.contains(s)) order.add(s);
            }
            for (String eng : order) {
                TranslationEngine t = build(settings, eng);
                if (t != null) chain.add(t);
            }

            if (chain.isEmpty()) return null;
            if (chain.size() == 1) return chain.get(0);
            return new ChainTranslationEngine(chain);
        }

        /** 按引擎 key 构建单个引擎；未配置返回 null */
        private static TranslationEngine build(AppSettings settings, String eng) {
            switch (eng == null ? "" : eng) {
                case "deepseek": {
                    String k = settings.getDeepseekKey();
                    return (k == null || k.isEmpty()) ? null
                            : new OpenAICompatTranslationEngine("DeepSeek",
                                    "https://api.deepseek.com/chat/completions", "deepseek-chat", k);
                }
                case "zhipu": {
                    String k = settings.getZhipuKey();
                    return (k == null || k.isEmpty()) ? null
                            : new OpenAICompatTranslationEngine("智谱GLM",
                                    "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash", k);
                }
                case "alibaba": {
                    String k = settings.getAlibabaKey();
                    return (k == null || k.isEmpty()) ? null
                            : new OpenAICompatTranslationEngine("阿里通义",
                                    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-turbo", k);
                }
                case "baidu": {
                    String appid = settings.getBaiduAppid();
                    String key = settings.getBaiduKey();
                    return (appid == null || appid.isEmpty() || key == null || key.isEmpty())
                            ? null : new BaiduTranslationEngine(appid, key);
                }
                default:
                    return null;
            }
        }
    }
}
