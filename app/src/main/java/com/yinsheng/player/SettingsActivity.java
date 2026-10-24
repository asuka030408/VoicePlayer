package com.yinsheng.player;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页（精简版）：
 *  - 识别引擎（SenseVoice / Whisper，独立版本对比用）
 *  - 字幕开关
 *  - 字幕切片时长（30 秒步进，默认 1 分钟）
 *  - 翻译引擎（百度国内直连 / DeepSeek LLM）+ 对应密钥填写
 *  - 「保存设置」按钮 + 返回时自动保存（双保险）
 */
public class SettingsActivity extends Activity {

    private AppSettings settings;
    private Switch swFloating;
    private Switch swOfflineAsr;
    private boolean updatingFloating;
    private boolean awaitingOverlayPermission;
    private EditText etBaiduAppid;
    private EditText etBaiduKey;
    private EditText etDeepseekKey;
    private EditText etZhipuKey;
    private Spinner spAsr;
    private Spinner spTrans;
    private Spinner spChunk;
    private Spinner spOffset;
    // ---- 联网 ASR ----
    private Switch swCloudAsr;
    private boolean cloudAsrConfirming = false;   // 确认/取消回调里程序性 setChecked 的防重入标志
    private Spinner spCloudAsr;
    private EditText etTencentId, etTencentKey;
    private EditText etAlibabaAppkey;
    private EditText etIflytekAppid, etIflytekApiKey, etIflytekApiSecret;

    /** ASR 引擎选择 */
    private static final String[] ASR_KEYS = {"sensevoice", "whisper"};
    private static final String[] ASR_LABELS = {"SenseVoice（快，默认）", "Whisper（抗噪更强）"};

    /** 联网 ASR 引擎选择（云端识别，按音频时长计费） */
    private static final String[] CLOUD_ASR_KEYS = {"tencent", "alibaba", "iflytek"};
    private static final String[] CLOUD_ASR_LABELS = {"腾讯云", "阿里云", "讯飞"};

    /** 翻译引擎选择（首选；其余已填密钥的厂商自动参与兜底） */
    private static final String[] TRANS_KEYS = {"deepseek", "baidu", "zhipu", "alibaba"};
    private static final String[] TRANS_LABELS = {"DeepSeek LLM（默认）", "百度翻译（免费额度）", "智谱 GLM（免费）", "阿里通义（便宜）"};

    /** 可选切片时长（秒），半分钟步进 */
    private static final int[] CHUNK_VALUES = {30, 60, 90, 120, 180};
    private static final String[] CHUNK_LABELS = {"30 秒", "1 分钟", "1 分 30 秒", "2 分钟", "3 分钟"};

    /** 字幕时间偏移（毫秒），半秒步进：负=字幕提前显示（声音滞后），正=字幕延后显示 */
    private static final int[] OFFSET_VALUES = {-2000, -1500, -1000, -500, 0, 500, 1000, 1500, 2000};
    private static final String[] OFFSET_LABELS = {"提前 2.0 秒", "提前 1.5 秒", "提前 1.0 秒", "提前 0.5 秒",
            "同步（默认）", "延后 0.5 秒", "延后 1.0 秒", "延后 1.5 秒", "延后 2.0 秒"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // 状态栏透明（theme 已设），ScrollView 的 fitsSystemWindows 让深黑背景自动延伸到状态栏
        settings = new AppSettings(this);

        // 底部版本号：直接读构建配置，与 build.gradle 的 versionName 一致
        TextView txtVersion = findViewById(R.id.txt_version);
        txtVersion.setText("v" + BuildConfig.VERSION_NAME);

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // 识别引擎
        spAsr = findViewById(R.id.sp_asr);
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ASR_LABELS);
        asrAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAsr.setAdapter(asrAdapter);
        String asr = settings.getAsrEngine();
        int asrSel = 0;
        for (int i = 0; i < ASR_KEYS.length; i++) {
            if (ASR_KEYS[i].equals(asr)) { asrSel = i; break; }
        }
        spAsr.setSelection(asrSel);

        swOfflineAsr = findViewById(R.id.sw_offline_asr);
        swOfflineAsr.setChecked(settings.isOfflineAsrEnabled());
        swOfflineAsr.setOnCheckedChangeListener((button, enabled) -> settings.setOfflineAsrEnabled(enabled));

        // ---- 联网 ASR 引擎 ----
        swCloudAsr = findViewById(R.id.sw_cloud_asr);
        swCloudAsr.setChecked(settings.isCloudAsrEnabled());
        // 开启联网 ASR：先弹确认框（密钥检测 + 开销提示 + 18+ 警告），确认才真正打开并优先使用。
        // cloudAsrConfirming 防重入：确认/取消回调里程序性 setChecked 不应再触发弹窗（否则无限循环）
        swCloudAsr.setOnCheckedChangeListener((b, isOn) -> {
            if (cloudAsrConfirming) return;
            if (!isOn) {
                settings.setCloudAsrEnabled(false);
                return;
            }
            // 先把开关弹回关闭状态，确认后才置为开启
            swCloudAsr.setChecked(false);
            showCloudAsrConfirmDialog();
        });

        spCloudAsr = findViewById(R.id.sp_cloud_asr);
        ArrayAdapter<String> cloudAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CLOUD_ASR_LABELS);
        cloudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCloudAsr.setAdapter(cloudAdapter);
        String cloudEngine = settings.getCloudAsrEngine();
        int cloudSel = 0;
        for (int i = 0; i < CLOUD_ASR_KEYS.length; i++) {
            if (CLOUD_ASR_KEYS[i].equals(cloudEngine)) { cloudSel = i; break; }
        }
        spCloudAsr.setSelection(cloudSel);

        // 云端厂商密钥输入框（失焦即保存，与翻译密钥一致）
        etTencentId = findViewById(R.id.et_tencent_id);
        etTencentId.setText(settings.getTencentId());
        etTencentId.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });
        etTencentKey = findViewById(R.id.et_tencent_key);
        etTencentKey.setText(settings.getTencentKey());
        etTencentKey.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });

        etAlibabaAppkey = findViewById(R.id.et_alibaba_appkey);
        etAlibabaAppkey.setText(settings.getAlibabaKey());   // 百炼 API Key（与翻译共用）
        etAlibabaAppkey.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });

        etIflytekAppid = findViewById(R.id.et_iflytek_appid);
        etIflytekAppid.setText(settings.getIflytekAppId());
        etIflytekAppid.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });
        etIflytekApiKey = findViewById(R.id.et_iflytek_apikey);
        etIflytekApiKey.setText(settings.getIflytekApiKey());
        etIflytekApiKey.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });
        etIflytekApiSecret = findViewById(R.id.et_iflytek_apisecret);
        etIflytekApiSecret.setText(settings.getIflytekApiSecret());
        etIflytekApiSecret.setOnFocusChangeListener((v, h) -> { if (!h) saveAll(); });

        swFloating = findViewById(R.id.sw_floating_subtitles);
        refreshFloatingSwitch();
        swFloating.setOnCheckedChangeListener((button, enabled) -> {
            if (updatingFloating) return;
            if (!enabled) { settings.setFloatingSubtitlesEnabled(false); return; }
            if (android.provider.Settings.canDrawOverlays(this)) {
                settings.setFloatingSubtitlesEnabled(true);
            } else {
                settings.setFloatingSubtitlesEnabled(false);
                refreshFloatingSwitch();
                awaitingOverlayPermission = true;
                Toast.makeText(this, "请允许显示在其他应用上层；返回后将开启悬浮字幕", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    awaitingOverlayPermission = false;
                    Toast.makeText(this, "请在系统设置中授予悬浮窗权限，再开启此开关", Toast.LENGTH_LONG).show();
                }
            }
        });

        // 字幕切片时长（半分钟步进）
        spChunk = findViewById(R.id.sp_chunk);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CHUNK_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spChunk.setAdapter(adapter);
        int cur = settings.getChunkSeconds();
        int sel = 1;   // 默认 1 分钟
        for (int i = 0; i < CHUNK_VALUES.length; i++) {
            if (CHUNK_VALUES[i] == cur) { sel = i; break; }
        }
        spChunk.setSelection(sel);

        // 字幕时间偏移（半秒步进）
        spOffset = findViewById(R.id.sp_offset);
        ArrayAdapter<String> offAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, OFFSET_LABELS);
        offAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spOffset.setAdapter(offAdapter);
        int curOff = settings.getSubOffsetMs();
        int offSel = 4;   // 默认 0
        for (int i = 0; i < OFFSET_VALUES.length; i++) {
            if (OFFSET_VALUES[i] == curOff) { offSel = i; break; }
        }
        spOffset.setSelection(offSel);

        // 翻译引擎
        spTrans = findViewById(R.id.sp_trans);
        ArrayAdapter<String> transAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, TRANS_LABELS);
        transAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTrans.setAdapter(transAdapter);
        String trans = settings.getTransEngine();
        int transSel = 0;
        for (int i = 0; i < TRANS_KEYS.length; i++) {
            if (TRANS_KEYS[i].equals(trans)) { transSel = i; break; }
        }
        spTrans.setSelection(transSel);

        // 百度翻译（国内直连，优先使用）
        etBaiduAppid = findViewById(R.id.et_baidu_appid);
        etBaiduAppid.setText(settings.getBaiduAppid());
        etBaiduAppid.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveAll();   // 失焦即保存（双保险）
        });
        etBaiduKey = findViewById(R.id.et_baidu_key);
        etBaiduKey.setText(settings.getBaiduKey());
        etBaiduKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveAll();
        });

        // DeepSeek API Key
        etDeepseekKey = findViewById(R.id.et_deepseek_key);
        etDeepseekKey.setText(settings.getDeepseekKey());
        etDeepseekKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveAll();
        });

        // 智谱（兜底厂商）；阿里通义翻译共用联网 ASR 区的百炼 API Key（下方已填）
        etZhipuKey = findViewById(R.id.et_zhipu_key);
        etZhipuKey.setText(settings.getZhipuKey());
        etZhipuKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveAll();
        });

        // 保存设置按钮：显式保存后返回
        TextView btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /**
     * 页面退出（返回键 / finish / 切后台）时统一保存。
     * 仅靠"输入框失焦"保存会漏掉：输入完直接按返回键时输入框从未失焦。
     */
    @Override
    protected void onStart() {
        super.onStart();
        MainActivity.activityStarted();
    }

    @Override
    protected void onStop() {
        MainActivity.activityStopped();
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAll();
    }

    private void refreshFloatingSwitch() {
        if (swFloating == null) return;
        updatingFloating = true;
        swFloating.setChecked(settings.isFloatingSubtitlesEnabled() && android.provider.Settings.canDrawOverlays(this));
        updatingFloating = false;
    }
    @Override protected void onResume() {
        super.onResume();
        if (settings == null) return;
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            settings.setFloatingSubtitlesEnabled(android.provider.Settings.canDrawOverlays(this));
        } else if (!android.provider.Settings.canDrawOverlays(this)) settings.setFloatingSubtitlesEnabled(false);
        refreshFloatingSwitch();
    }

    private void saveAll() {
        if (settings == null) return;
        if (spAsr != null && spAsr.getSelectedItemPosition() >= 0) {
            settings.setAsrEngine(ASR_KEYS[spAsr.getSelectedItemPosition()]);
        }
        if (spCloudAsr != null && spCloudAsr.getSelectedItemPosition() >= 0) {
            settings.setCloudAsrEngine(CLOUD_ASR_KEYS[spCloudAsr.getSelectedItemPosition()]);
        }
        if (etTencentId != null) settings.setTencentId(etTencentId.getText().toString().trim());
        if (etTencentKey != null) settings.setTencentKey(etTencentKey.getText().toString().trim());
        if (etAlibabaAppkey != null) settings.setAlibabaKey(etAlibabaAppkey.getText().toString().trim());
        if (etIflytekAppid != null) settings.setIflytekAppId(etIflytekAppid.getText().toString().trim());
        if (etIflytekApiKey != null) settings.setIflytekApiKey(etIflytekApiKey.getText().toString().trim());
        if (etIflytekApiSecret != null) settings.setIflytekApiSecret(etIflytekApiSecret.getText().toString().trim());
        if (etBaiduAppid != null) settings.setBaiduAppid(etBaiduAppid.getText().toString().trim());
        if (etBaiduKey != null) settings.setBaiduKey(etBaiduKey.getText().toString().trim());
        if (etDeepseekKey != null) settings.setDeepseekKey(etDeepseekKey.getText().toString().trim());
        if (etZhipuKey != null) settings.setZhipuKey(etZhipuKey.getText().toString().trim());
        if (spChunk != null && spChunk.getSelectedItemPosition() >= 0) {
            settings.setChunkSeconds(CHUNK_VALUES[spChunk.getSelectedItemPosition()]);
        }
        if (spOffset != null && spOffset.getSelectedItemPosition() >= 0) {
            settings.setSubOffsetMs(OFFSET_VALUES[spOffset.getSelectedItemPosition()]);
        }
        // 翻译引擎联动：以「实际填了哪家的密钥」为准。首选没配置时自动切到第一个已配置的厂商；
        // 其余已配置厂商自动参与兜底（额度用尽自动切换）
        if (spTrans != null) {
            String engine = TRANS_KEYS[spTrans.getSelectedItemPosition()];
            boolean dskOk = !settings.getDeepseekKey().isEmpty();
            boolean baiduOk = !settings.getBaiduAppid().isEmpty() && !settings.getBaiduKey().isEmpty();
            boolean zhipuOk = !settings.getZhipuKey().isEmpty();
            boolean alibabaOk = !settings.getAlibabaKey().isEmpty();
            // 首选没配 → 自动切到第一个已配置的
            if (!isEngineConfigured(engine, dskOk, baiduOk, zhipuOk, alibabaOk)) {
                String fallback = null;
                for (String e : new String[]{"deepseek", "baidu", "zhipu", "alibaba"}) {
                    if (isEngineConfigured(e, dskOk, baiduOk, zhipuOk, alibabaOk)) { fallback = e; break; }
                }
                if (fallback != null) {
                    engine = fallback;
                    for (int i = 0; i < TRANS_KEYS.length; i++) {
                        if (TRANS_KEYS[i].equals(fallback)) { spTrans.setSelection(i); break; }
                    }
                    Toast.makeText(this, "首选未配置，已自动切换到 " + TRANS_LABELS[java.util.Arrays.asList(TRANS_KEYS).indexOf(fallback)], Toast.LENGTH_SHORT).show();
                }
            }
            if (dskOk || baiduOk || zhipuOk || alibabaOk) {
                settings.setTransEngine(engine);
            } else {
                settings.setTransEngine(engine);   // 一个都没配：保留选择，翻译暂不可用（提示）
                Toast.makeText(this, "尚未填写任何翻译密钥，翻译暂不可用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static boolean isEngineConfigured(String engine, boolean dsk, boolean baidu, boolean zhipu, boolean alibaba) {
        switch (engine) {
            case "deepseek": return dsk;
            case "baidu": return baidu;
            case "zhipu": return zhipu;
            case "alibaba": return alibaba;
            default: return false;
        }
    }

    // ---------------------------------------------------------------
    // 联网 ASR 开关确认弹窗（密钥检测 + 开销提示 + 18+ 警告）
    // ---------------------------------------------------------------
    private void showCloudAsrConfirmDialog() {
        saveAll();   // 先保存厂商选择与密钥，弹窗内检测基于最新输入
        final String vendor = CLOUD_ASR_LABELS[java.util.Arrays.asList(CLOUD_ASR_KEYS).indexOf(settings.getCloudAsrEngine())];
        final boolean configured = settings.isCloudAsrConfigured();

        StringBuilder msg = new StringBuilder();
        msg.append("将开启联网 ASR 引擎（").append(vendor).append("）。\n\n");
        if (configured) {
            msg.append("✔ 已检测到所选厂商的密钥。\n\n");
        } else {
            msg.append("✘ 尚未填写所选厂商的密钥/ID，开启后「后台生成」会直接失败。\n")
               .append("（腾讯云：SecretId/SecretKey；阿里云：百炼 API Key；讯飞：APPID/APIKey/APISecret）\n\n");
        }
        msg.append("【开销提示】音频分片将上传到 ").append(vendor).append(" 云端识别，按音频时长计费：\n")
           .append("腾讯云约 0.013 元/分钟；阿里云(百炼 qwen3-asr-flash)约 0.8 元/小时；讯飞约 16.5~23 元/万次（中文）。\n")
           .append("识别结果会缓存，同一首歌不会重复计费。\n\n")
           .append("【重要警告】不得用于 18+ 内容！涉黄内容会被云平台内容审核识别并可能导致账号封禁。\n")
           .append("敏感内容请务必继续使用离线引擎（音频不出手机）。");

        new android.app.AlertDialog.Builder(this)
                .setTitle("开启联网 ASR 引擎？")
                .setMessage(msg.toString())
                .setPositiveButton("确认开启", (d, w) -> {
                    cloudAsrConfirming = true;      // 防重入：程序性置位不再触发弹窗
                    settings.setCloudAsrEnabled(true);
                    swCloudAsr.setChecked(true);
                    cloudAsrConfirming = false;
                    Toast.makeText(this, "联网 ASR 已开启（前台字幕生成与后台生成均优先使用）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, w) -> {
                    cloudAsrConfirming = true;
                    swCloudAsr.setChecked(false);   // 保持关闭
                    cloudAsrConfirming = false;
                })
                .show();
    }
}
