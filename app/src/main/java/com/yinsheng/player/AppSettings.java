package com.yinsheng.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用设置（SharedPreferences 封装）。
 *  - 字幕开关
 *  - ASR 识别引擎：sensevoice（默认，快）/ whisper（抗噪更强、更慢）
 *  - 翻译引擎：baidu（国内直连）/ deepseek（LLM，需 API Key）
 *  - 百度翻译 APP ID / 密钥；DeepSeek API Key
 *  - 字幕处理方式固定为：1 分钟分片增量（≤1 分钟音频完整预加载后再播放）
 */
public class AppSettings {

    private static final String PREFS = "music_player_settings";
    private static final String KEY_SUBTITLE = "subtitle_enabled";
    private static final String KEY_TRANSLATION = "translation_enabled";
    private static final String KEY_BAIDU_APPID = "baidu_appid";
    private static final String KEY_BAIDU_KEY = "baidu_key";
    private static final String KEY_ASR_ENGINE = "asr_engine";
    private static final String KEY_TRANS_ENGINE = "trans_engine";
    private static final String KEY_DEEPSEEK_KEY = "deepseek_key";
    private static final String KEY_ZHIPU_KEY = "zhipu_key";
    private static final String KEY_ALIBABA_KEY = "alibaba_key";
    private static final String KEY_CHUNK_SECONDS = "chunk_seconds";
    private static final String KEY_SUB_OFFSET = "sub_offset_ms";
    /** 联网 ASR（云端识别，按音频时长计费） */
    private static final String KEY_CLOUD_ASR_ENABLED = "cloud_asr_enabled";
    /** 离线 ASR 开关（默认开启，保留原有本地识别行为） */
    private static final String KEY_OFFLINE_ASR_ENABLED = "offline_asr_enabled";
    private static final String KEY_CLOUD_ASR_ENGINE = "cloud_asr_engine";   // tencent / alibaba / iflytek
    private static final String KEY_TENCENT_ID = "tencent_secret_id";
    private static final String KEY_TENCENT_KEY = "tencent_secret_key";
    private static final String KEY_IFLYTEK_APPID = "iflytek_app_id";
    private static final String KEY_IFLYTEK_APIKEY = "iflytek_api_key";
    private static final String KEY_IFLYTEK_APISECRET = "iflytek_api_secret";
    /** 生成完毕记录：audioKey -> 完成时间戳（毫秒），供「生成完毕」tab 排序 */
    private static final String KEY_DONE_RECORDS = "done_records";

    private final SharedPreferences sp;

    public AppSettings(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences preferences() { return sp; }
    public boolean isFloatingSubtitlesEnabled() { return sp.getBoolean("floating_subtitles", false); }
    public void setFloatingSubtitlesEnabled(boolean enabled) { sp.edit().putBoolean("floating_subtitles", enabled).apply(); }
    public boolean isTranslationConfigured() {
        switch (getTransEngine()) {
            case "deepseek": return !getDeepseekKey().trim().isEmpty();
            case "zhipu": return !getZhipuKey().trim().isEmpty();
            case "alibaba": return !getAlibabaKey().trim().isEmpty();
            case "baidu": return !getBaiduAppid().trim().isEmpty() && !getBaiduKey().trim().isEmpty();
            default: return false;
        }
    }

    /** 字幕开关（默认关闭——由播放界面「字幕」按钮主动开启，不默认对每首歌生成） */
    public boolean isSubtitleEnabled() { return sp.getBoolean(KEY_SUBTITLE, false); }
    public void setSubtitleEnabled(boolean b) { sp.edit().putBoolean(KEY_SUBTITLE, b).apply(); }

    /** 翻译开关（默认关闭——字幕识别是本地免费，翻译消耗额度/Key，需用户主动开启；
     *  开启后对后续分片生效，并对已生成但无译文的分片后台补翻译，已翻译过的句子不重复消耗） */
    public boolean isTranslationEnabled() { return sp.getBoolean(KEY_TRANSLATION, false); }
    public void setTranslationEnabled(boolean b) { sp.edit().putBoolean(KEY_TRANSLATION, b).apply(); }

    /** ASR 识别引擎："sensevoice"（默认，快）/ "whisper"（抗噪更强、速度较慢） */
    public String getAsrEngine() { return sp.getString(KEY_ASR_ENGINE, "sensevoice"); }
    public void setAsrEngine(String s) { sp.edit().putString(KEY_ASR_ENGINE, s).apply(); }

    /** 翻译引擎："baidu"（国内直连）/ "deepseek"（LLM，需 API Key） */
    public String getTransEngine() { return sp.getString(KEY_TRANS_ENGINE, "baidu"); }
    public void setTransEngine(String s) { sp.edit().putString(KEY_TRANS_ENGINE, s).apply(); }

    /** 百度翻译开放平台（国内直连，无需 VPN）。appid + key 均非空时优先使用 */
    public String getBaiduAppid() { return sp.getString(KEY_BAIDU_APPID, ""); }
    public void setBaiduAppid(String s) { sp.edit().putString(KEY_BAIDU_APPID, s).apply(); }
    public String getBaiduKey() { return sp.getString(KEY_BAIDU_KEY, ""); }
    public void setBaiduKey(String s) { sp.edit().putString(KEY_BAIDU_KEY, s).apply(); }

    /** DeepSeek 开放平台 API Key（https://platform.deepseek.com → API Keys） */
    public String getDeepseekKey() { return sp.getString(KEY_DEEPSEEK_KEY, ""); }
    public void setDeepseekKey(String s) { sp.edit().putString(KEY_DEEPSEEK_KEY, s).apply(); }

    /** 智谱开放平台 API Key（https://open.bigmodel.cn → API 密钥；GLM-4-Flash 免费模型） */
    public String getZhipuKey() { return sp.getString(KEY_ZHIPU_KEY, ""); }
    public void setZhipuKey(String s) { sp.edit().putString(KEY_ZHIPU_KEY, s).apply(); }

    /** 阿里云百炼 API Key（https://bailian.console.aliyun.com → API-KEY；qwen-turbo 便宜）。
     *  一把 Key 两用：翻译兜底（阿里通义 qwen-turbo）+ 联网 ASR（qwen3-asr-flash 识别） */
    public String getAlibabaKey() { return sp.getString(KEY_ALIBABA_KEY, ""); }
    public void setAlibabaKey(String s) { sp.edit().putString(KEY_ALIBABA_KEY, s).apply(); }

    /** 字幕切片时长（秒），以半分钟为单位步进；默认 60 秒。说话快/音频长时可按需调整 */
    public int getChunkSeconds() { return sp.getInt(KEY_CHUNK_SECONDS, 60); }
    public void setChunkSeconds(int s) { sp.edit().putInt(KEY_CHUNK_SECONDS, s).apply(); }

    /** 字幕时间偏移（毫秒，-2000 ~ +2000，半秒步进；默认 0）。
     *  蓝牙耳机等输出延迟时字幕相对声音超前/滞后，可用偏移校准：声音比字幕晚 → 用负值提前匹配 */
    public int getSubOffsetMs() { return sp.getInt(KEY_SUB_OFFSET, 0); }
    public void setSubOffsetMs(int ms) { sp.edit().putInt(KEY_SUB_OFFSET, ms).apply(); }

    // ---------- 联网 ASR（云端识别，涉及计费，默认关闭） ----------

    public boolean isCloudAsrEnabled() { return sp.getBoolean(KEY_CLOUD_ASR_ENABLED, false); }
    public void setCloudAsrEnabled(boolean b) { sp.edit().putBoolean(KEY_CLOUD_ASR_ENABLED, b).apply(); }

    public boolean isOfflineAsrEnabled() { return sp.getBoolean(KEY_OFFLINE_ASR_ENABLED, true); }
    public void setOfflineAsrEnabled(boolean b) { sp.edit().putBoolean(KEY_OFFLINE_ASR_ENABLED, b).apply(); }

    /** 联网 ASR 厂商："tencent" / "alibaba" / "iflytek"；默认腾讯云 */
    public String getCloudAsrEngine() { return sp.getString(KEY_CLOUD_ASR_ENGINE, "tencent"); }
    public void setCloudAsrEngine(String s) { sp.edit().putString(KEY_CLOUD_ASR_ENGINE, s).apply(); }

    /** 联网 ASR 厂商是否已配置密钥（ID + Key 都非空才算配置好） */
    public boolean isCloudAsrConfigured() {
        switch (getCloudAsrEngine()) {
            case "tencent": return !getTencentId().isEmpty() && !getTencentKey().isEmpty();
            case "alibaba": return !getAlibabaKey().isEmpty();   // 百炼 API Key（与翻译共用）
            case "iflytek": return !getIflytekAppId().isEmpty() && !getIflytekApiKey().isEmpty() && !getIflytekApiSecret().isEmpty();
            default: return false;
        }
    }

    public String getTencentId() { return sp.getString(KEY_TENCENT_ID, ""); }
    public void setTencentId(String s) { sp.edit().putString(KEY_TENCENT_ID, s).apply(); }
    public String getTencentKey() { return sp.getString(KEY_TENCENT_KEY, ""); }
    public void setTencentKey(String s) { sp.edit().putString(KEY_TENCENT_KEY, s).apply(); }
    public String getIflytekAppId() { return sp.getString(KEY_IFLYTEK_APPID, ""); }
    public void setIflytekAppId(String s) { sp.edit().putString(KEY_IFLYTEK_APPID, s).apply(); }
    public String getIflytekApiKey() { return sp.getString(KEY_IFLYTEK_APIKEY, ""); }
    public void setIflytekApiKey(String s) { sp.edit().putString(KEY_IFLYTEK_APIKEY, s).apply(); }
    public String getIflytekApiSecret() { return sp.getString(KEY_IFLYTEK_APISECRET, ""); }
    public void setIflytekApiSecret(String s) { sp.edit().putString(KEY_IFLYTEK_APISECRET, s).apply(); }

    // ---------- 生成完毕记录（audioKey -> 完成时间） ----------

    public void putDoneRecord(String audioKey, long timeMs) {
        try {
            java.util.Set<String> rec = getDoneRecordsRaw();
            rec.remove(audioKey + "|" + getDoneTime(audioKey));   // 移除旧条目（若存在）
            rec.add(audioKey + "|" + timeMs);
            sp.edit().putStringSet(KEY_DONE_RECORDS, rec).apply();
        } catch (Exception e) { sp.edit().remove(KEY_DONE_RECORDS).apply(); }
    }
    public java.util.Set<String> getDoneRecordsRaw() {
        return new java.util.HashSet<>(sp.getStringSet(KEY_DONE_RECORDS, new java.util.HashSet<>()));
    }
    public long getDoneTime(String audioKey) {
        for (String r : getDoneRecordsRaw()) {
            if (r.startsWith(audioKey + "|")) {
                try { return Long.parseLong(r.substring(audioKey.length() + 1)); } catch (Exception ignored) {}
            }
        }
        return 0;
    }
    public void removeDoneRecord(String audioKey) {
        java.util.Set<String> rec = getDoneRecordsRaw();
        java.util.Set<String> next = new java.util.HashSet<>();
        for (String r : rec) if (!r.startsWith(audioKey + "|")) next.add(r);
        sp.edit().putStringSet(KEY_DONE_RECORDS, next).apply();
    }
}
