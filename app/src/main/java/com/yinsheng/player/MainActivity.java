package com.yinsheng.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.content.ContentUris;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedDispatcher;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MainActivity extends Activity implements PlaybackSession.Ui {
    /** 由主界面与设置页共同维护；播放服务据此判断应用是否仍在前台。 */
    private static int foregroundActivityCount;
    public static synchronized void activityStarted() { foregroundActivityCount++; }
    public static synchronized void activityStopped() { foregroundActivityCount = Math.max(0, foregroundActivityCount - 1); }
    public static synchronized boolean isAppForeground() { return foregroundActivityCount > 0; }

    private static final int REQ_PERM = 100;
    private static final int REQ_PICK = 101;
    private static final int REQ_TREE = 102;
    private static final int REQ_DELETE = 103;
    private static final int MODE_SEQUENCE = 0, MODE_SINGLE = 1, MODE_SHUFFLE = 2;
    private static final int TAB_ALBUMS = 0, TAB_ALL = 1, TAB_FILES = 2, TAB_LIKES = 3, TAB_DONE = 4;
    private static final String KEY_FILES = "external_files";
    private static final String KEY_TREES = "external_trees";
    private static final String KEY_LIKED = "liked_songs";
    private static final String KEY_LAST_URI = "last_song_uri";
    private static final String KEY_LAST_PATH = "last_song_path";
    private static final String KEY_LAST_POS = "last_song_pos";
    private static final String KEY_LAST_PLAYING = "last_song_playing";

    private final ArrayList<Song> allSongs = new ArrayList<>();
    private final ArrayList<Song> shownSongs = new ArrayList<>();
    private final ArrayList<Song> fileSongs = new ArrayList<>();
    private final ArrayList<Song> likedSongs = new ArrayList<>();
    private final ArrayList<Album> albums = new ArrayList<>();
    private final Map<Long, String> artPathByAlbum = new HashMap<>();
    private final ArrayList<Uri> savedFiles = new ArrayList<>();
    private Uri pendingReplay;   // 授权失效重选中的文件 URI（重选成功后替换并播放）
    private final ArrayList<Uri> savedTrees = new ArrayList<>();
    private final Random random = new Random();
    private final Set<String> likedSet = new HashSet<>();   // 收藏标识：uri.toString() 或 "path:"+path
    private String pendingDeleteKey;   // 媒体库删除确认后待移除的歌曲身份

    private PlaybackSession player;
    private PlaybackSession playback;
    private String displayedSong;
    /** 歌单高光上次渲染对应的歌曲，避免播放进度刷新时重复重绘整个列表。 */
    private String renderedHighlightKey;
    private int current = -1;
    private Song currentSong;
    private int playMode = MODE_SEQUENCE;
    private boolean userSeeking = false;
    private Uri pendingViewUri;   // 通过「打开方式」进入时待播放的音频
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // ---- 字幕系统 ----
    private AppSettings settings;
    private SubtitleManager subMgr;
    private SubtitlePipeline pipeline;
    private String subKey;                 // 当前音频字幕标识
    private long subDuration;
    private int subTotalSegs;
    private final ArrayList<SubtitleManager.SubtitleLine> subLines = new ArrayList<>();
    private final Set<Integer> noSpeechSegs = new HashSet<>();
    private int subCurrentSeg = -1;
    private int subProgress = 0;
    private final Set<Integer> processedSegs = new HashSet<>();
    private final Set<Integer> cloudSegs = new HashSet<>();   // 联网 ASR 生成的分片（进度条蓝色标记）

    private GridView gridBox;
    private LinearLayout listBox;
    private View gridPane, listPane, emptyView, subtitleArea;
    private TextView txtCount, txtCur, txtTotal, txtNowTitle, txtNowArtist, listHeader, txtHeaderTitle;
    private TextView btnAlbums, btnAll, btnFiles, btnLikes, btnMode, btnAdd, btnSettings;
    private TextView btnDone;   // 「已生成」tab（生成完毕）
    private BulkGenerator bulkGen;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh, swipeRefreshGrid;
    private TextView btnSubtitle;
    private TextView btnTrans;
    private TextView fullTrans;
    private boolean backCallbackRegistered;
    private final android.window.OnBackInvokedCallback backCallback = this::handleSystemBack;
    /** 首次加载字幕等待标志：无缓存时先暂停播放，等分片 0（或全部）就绪后自动播放 */
    private boolean waitSubtitleStart;
    private String waitSubKey;
    private int waitSeg = -1;   // 等待就绪的目标分片（= 播放位置所在分片；恢复中段播放时等该分片）
    private String waitBulkKey;            // 播放的歌正在后台生成字幕：完成后自动加载缓存
    private boolean fullCachePlayback;     // 当前播放的歌有完整字幕缓存（字幕与开关解耦，常显）
    private TextView txtSubStatus, txtSubOrigin, txtSubTrans, txtSubNoSpeech, btnComplete;
    private TextView txtEngineBadge;   // 常驻引擎标识：联网·厂商 / 离线·引擎
    private boolean activityVisible;
    private String pendingEngineError;

    // 全屏滚动字幕页（动态 addContentView，点击字幕区进入，类似网易云滚动歌词）
    private View fullOverlay;
    private ScrollView fullScroll;
    private LinearLayout fullList;
    private TextView fullTitle;
    private TextView fullHint;
    /** 全屏字幕页跟随模式：true=自动跟随当前句滚动（默认）；用户手动滑动后变 false（自由浏览），点击句子/提示条恢复 */
    private boolean fullFollow = true;
    private int lastFullActive = -1;   // 全屏页当前高亮行索引（增量刷新用，重建列表时重置）
    private static final class FullRowTag {
        final SubtitleManager.SubtitleLine line;
        final int segment;
        FullRowTag(SubtitleManager.SubtitleLine l, int s) { line = l; segment = s; }
    }
    private int subOffsetMs;   // 字幕时间偏移（设置页可调，校准蓝牙等输出延迟）
    private ProcessingProgressView progressView;
    private ImageView imgNowCover;
    private SeekBar seekBar;
    private ImageButton btnPlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 全面屏适配：状态栏透明，顶部渐变头部与底部播放栏分别吸收系统 insets
        // （状态栏区域显示渐变紫、底部避开手势条，视觉一体且不被遮挡）
        final View root = findViewById(R.id.root);
        final View headerBar = findViewById(R.id.header_bar);
        final View playerBar = findViewById(R.id.player_bar);
        final float d = getResources().getDisplayMetrics().density;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (headerBar != null) {
                headerBar.setPadding((int)(16*d), top + (int)(14*d), (int)(16*d), (int)(14*d));
            }
            if (playerBar != null) {
                playerBar.setPadding((int)(10*d), (int)(2*d), (int)(10*d), bottom + (int)(28*d));
            }
            return insets;
        });
        // 预测性返回回调在 onStart() 中注册（onCreate 里注册会被系统忽略）
        prefs = getSharedPreferences("music_player", MODE_PRIVATE);
        settings = new AppSettings(this);
        playback = PlaybackSession.get(this);
        subMgr = playback.cache;
        pipeline = playback.pipeline;
        playback.attach(this, pipelineListener);
        // 后台批量生成（长按 → 后台生成字幕）：进度变化时刷新「已生成」tab
        bulkGen = playback.bulk;
        bulkGen.setListener(() -> {
            if (viewState == VIEW_DONE && listPane.getVisibility() == View.VISIBLE) showDone();
            // 当前播放的歌曲在后台生成中：与后台共享进度，重载已生成分片（字幕渐进显示）
            if (waitBulkKey != null && currentSong != null) {
                long dur = player != null ? player.getDuration() : currentSong.duration;
                String id = currentSong.path != null ? currentSong.path : String.valueOf(currentSong.uri);
                String k = SubtitleManager.audioKey(id, dur);
                if (k.equals(waitBulkKey)) {
                    if (subMgr.hasFullCache(k)) {
                        // 后台完成 → 加载完整字幕（进入常显模式）
                        waitBulkKey = null;
                        setupSubtitle(currentSong, (int) dur);
                    } else {
                        // 后台进行中 → 重载已生成分片到字幕区，随后台进度渐进增加
                        subLines.clear();
                        List<Integer> done = subMgr.getProcessedSegments(k);
                        for (int seg : done) subLines.addAll(subMgr.loadSegment(k, seg));
                        sortSubLines();
                        int total = subMgr.segmentCount(dur);
                        txtSubStatus.setText("后台生成中 " + (total > 0 ? done.size() * 100 / total : 0) + "%");
                    }
                }
            }
        });

        // 文件关联：从外部「打开方式」进入时，记录待播放的音频 Uri
        if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            pendingViewUri = getIntent().getData();
        }

        gridBox = findViewById(R.id.grid_box);
        listBox = findViewById(R.id.list_box);
        gridPane = findViewById(R.id.grid_box);
        listPane = findViewById(R.id.pane_list);
        emptyView = findViewById(R.id.empty_view);
        subtitleArea = findViewById(R.id.subtitle_area);
        txtCount = findViewById(R.id.txt_count);
        txtHeaderTitle = findViewById(R.id.txt_title);
        txtCur = findViewById(R.id.txt_cur);
        txtTotal = findViewById(R.id.txt_total);
        txtNowTitle = findViewById(R.id.txt_now_title);
        txtNowArtist = findViewById(R.id.txt_now_artist);
        listHeader = findViewById(R.id.list_header);
        btnAlbums = findViewById(R.id.btn_albums);
        btnAll = findViewById(R.id.btn_all);
        btnFiles = findViewById(R.id.btn_files);
        btnLikes = findViewById(R.id.btn_likes);
        btnMode = findViewById(R.id.btn_mode);
        btnAdd = findViewById(R.id.btn_add);
        btnSettings = findViewById(R.id.btn_settings);
        btnSubtitle = findViewById(R.id.btn_subtitle);
        btnTrans = findViewById(R.id.btn_trans);
        txtSubStatus = findViewById(R.id.txt_sub_status);
        txtSubOrigin = findViewById(R.id.txt_sub_origin);
        txtSubTrans = findViewById(R.id.txt_sub_trans);
        txtSubNoSpeech = findViewById(R.id.txt_sub_nospeech);
        btnComplete = findViewById(R.id.btn_complete);
        txtEngineBadge = findViewById(R.id.txt_engine_badge);
        progressView = findViewById(R.id.progress_view);
        imgNowCover = findViewById(R.id.img_now_cover);
        seekBar = findViewById(R.id.seek_bar);
        btnPlay = findViewById(R.id.btn_play);
        ImageButton btnPrev = findViewById(R.id.btn_prev);
        ImageButton btnNext = findViewById(R.id.btn_next);

        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> {
            ArrayList<Song> pool = groupedMode ? flattenGroups() : shownSongs;
            if (pool.isEmpty()) return;
            int i = pool.indexOf(currentSong);
            if (i <= 0) i = pool.size();
            playIndexForSong(pool.get(i - 1));
        });
        btnNext.setOnClickListener(v -> {
            ArrayList<Song> pool = groupedMode ? flattenGroups() : shownSongs;
            if (pool.isEmpty()) return;
            int i = pool.indexOf(currentSong);
            if (i >= pool.size() - 1) i = -1;
            playIndexForSong(pool.get(i + 1));
        });
        btnAlbums.setOnClickListener(v -> showAlbums());
        btnAll.setOnClickListener(v -> showAllSongs());
        btnFiles.setOnClickListener(v -> showFiles());
        btnLikes.setOnClickListener(v -> showLikes());
        btnDone = findViewById(R.id.btn_done);
        btnDone.setOnClickListener(v -> showDone());
        btnMode.setOnClickListener(v -> cycleMode());
        btnAdd.setOnClickListener(v -> showAddMenu());

        // 下拉刷新：重新扫描媒体库 + 文件夹（新下载的歌曲会自动出现），刷新后停留在当前页面
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefreshGrid = findViewById(R.id.swipe_refresh_grid);
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener doRefresh =
                () -> {
                    loadAll();
                    refreshViews();
                    swipeRefresh.setRefreshing(false);
                    swipeRefreshGrid.setRefreshing(false);
                    Toast.makeText(this, "已刷新（媒体库 + 文件夹）", Toast.LENGTH_SHORT).show();
                };
        swipeRefresh.setColorSchemeResources(android.R.color.holo_purple, android.R.color.holo_blue_light);
        swipeRefresh.setOnRefreshListener(doRefresh);
        swipeRefreshGrid.setColorSchemeResources(android.R.color.holo_purple, android.R.color.holo_blue_light);
        swipeRefreshGrid.setOnRefreshListener(doRefresh);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnSubtitle.setOnClickListener(v -> toggleSubtitle());
        btnTrans.setOnClickListener(v -> toggleTranslation());
        btnComplete.setOnClickListener(v -> pipeline.processAll());
        subtitleArea.setOnClickListener(v -> openFullSubtitle());   // 点击字幕区 → 全屏滚动字幕页
        refreshSubtitleButton();
        refreshTransButton();
        if (!settings.isSubtitleEnabled()) subtitleArea.setVisibility(View.GONE);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null) {
                    player.seekTo(s.getProgress());
                    onSeekTo(s.getProgress());
                }
            }
        });

        // 看门狗：上次严重卡死自动重启 → 提示已恢复（分片字幕缓存实时落盘，进度不丢）
        if (prefs.getBoolean(KEY_WATCHDOG_RESTART, false)) {
            prefs.edit().putBoolean(KEY_WATCHDOG_RESTART, false).apply();
            Toast.makeText(this, "上次界面无响应，已自动恢复；字幕进度已保留", Toast.LENGTH_LONG).show();
        }
        // Removed process-killing watchdog.
        // startWatchdog();   // 主线程看门狗：卡死自动兜底（停后台任务 → 仍卡则重启）

        if (hasPermission()) {
            loadAll();   // 若为外部「打开方式」进入，handlePendingView 已切到文件页
            if (viewState == VIEW_ALBUMS) showAlbums();
            if (pendingViewUri == null) restoreLastPlayback();
        } else requestPermissions(permissions(), REQ_PERM);
    }

    // ---------- 权限 ----------

    private String[] permissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : permissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) {
                loadAll();
                if (viewState == VIEW_ALBUMS) showAlbums();
                if (pendingViewUri == null) restoreLastPlayback();
            }
            else if (perms.length > 0 && !shouldShowRequestPermissionRationale(perms[0])) {
                // 用户勾选过"不再询问"：系统不再弹窗，引导去系统设置手动开启
                new AlertDialog.Builder(this)
                        .setTitle("需要音乐权限")
                        .setMessage("请在系统设置中允许「音声播放器」访问音乐和音频，才能扫描本地歌曲。")
                        .setPositiveButton("去设置", (d, w) -> {
                            try {
                                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                Toast.makeText(this, "需要音乐权限才能扫描本地歌曲", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- 数据加载 ----------

    private void loadAll() {
        loadSongs();
        loadAlbums();
        loadExternalFiles();
        loadLikedSongs();
        txtCount.setText("共 " + allSongs.size() + " 首");
        emptyView.setVisibility(allSongs.isEmpty() && fileSongs.isEmpty() ? View.VISIBLE : View.GONE);
        // 注意：这里不切换视图。首次启动由 onCreate 调用 showAlbums() 进入歌单页；
        // 下拉刷新复用 loadAll 时，由 refreshViews() 按当前视图状态精确恢复。
        handlePendingView();
    }

    /** App 已在运行时又被「打开方式」调用（单实例复用） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            pendingViewUri = intent.getData();
            handlePendingView();
        }
    }

    /** 处理外部打开的音频：加入文件列表并立即播放 */
    private void handlePendingView() {
        if (pendingViewUri == null) return;
        Uri u = pendingViewUri;
        pendingViewUri = null;
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        // 去重后加入文件列表
        boolean exists = false;
        for (Uri f : savedFiles) if (f.equals(u)) { exists = true; break; }
        if (!exists) {
            savedFiles.add(u);
            saveUris(KEY_FILES, savedFiles);
            String name = queryName(u);
            fileSongs.add(new Song(name, "文件", 0, null, u, 0));
        }
        showFiles();
        int idx = -1;
        for (int i = 0; i < shownSongs.size(); i++) {
            if (shownSongs.get(i).uri != null && shownSongs.get(i).uri.equals(u)) { idx = i; break; }
        }
        if (idx >= 0) {
            playIndex(idx);
            Toast.makeText(this, "正在播放：" + shownSongs.get(idx).title, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSongs() {
        allSongs.clear();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATE_ADDED};
        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        // 最新加入媒体库的排在最前
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                cols, sel, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iP = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                int iL = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int iAdd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                while (c.moveToNext()) {
                    String path = c.getString(iP);
                    long id = c.getLong(iId);
                    // 统一用 MediaStore content URI 作为播放源（Android 10+ 推荐）。
                    // 不要依赖 DATA 路径播放：Android 13+ 即使有 READ_MEDIA_AUDIO 也拒绝直接路径读取。
                    if (path == null && id <= 0) continue;
                    Uri mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    Song s = new Song(c.getString(iT), c.getString(iA), c.getLong(iD),
                            path, mediaUri, c.getLong(iL));
                    s.lastModified = c.getLong(iAdd) * 1000L;   // DATE_ADDED 单位秒 → 毫秒
                    allSongs.add(s);
                }
            }
        }
    }

    private void loadAlbums() {
        albums.clear();
        artPathByAlbum.clear();
        String[] cols = {MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM,
                MediaStore.Audio.Albums.ARTIST, MediaStore.Audio.Albums.ALBUM_ART};
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                cols, null, null, MediaStore.Audio.Albums.ALBUM + " ASC")) {
            if (c != null) {
                int iI = c.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID);
                int iN = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM);
                int iA = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST);
                int iC = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM_ART);
                while (c.moveToNext()) {
                    long id = c.getLong(iI);
                    albums.add(new Album(id, c.getString(iN), c.getString(iA), c.getString(iC)));
                    artPathByAlbum.put(id, c.getString(iC));
                }
            }
        }
    }

    // ---------- 手动添加文件 ----------

    private void loadExternalFiles() {
        fileSongs.clear();
        savedFiles.clear();
        savedTrees.clear();
        // 先扫文件夹（拥有真实路径身份），再加载单文件（与文件夹重复的跳过）
        Set<String> ts = prefs.getStringSet(KEY_TREES, new HashSet<>());
        for (String s : ts) {
            Uri u = Uri.parse(s);
            savedTrees.add(u);
            scanTree(u, treeLabelOf(u));
        }
        Set<String> fs = prefs.getStringSet(KEY_FILES, new HashSet<>());
        for (String s : fs) {
            Uri u = Uri.parse(s);
            savedFiles.add(u);
            String name = queryName(u);
            if (name != null) {
                Song song = new Song(name, "文件", 0, resolveFileRealPath(u, name), u, 0);
                song.source = 1;
                if (!songExistsInFileSongs(song)) fileSongs.add(song);
            }
        }
        mergeAllSongs();
    }

    private boolean songExistsInFileSongs(Song s) {
        String k = songKey(s);
        for (Song x : fileSongs) if (songKey(x).equals(k)) return true;
        return false;
    }

    /** 从 tree URI 解析可读文件夹名（如 primary:Download → Download） */
    private String treeLabelOf(Uri tree) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            int colon = docId.indexOf(':');
            String seg = colon >= 0 ? docId.substring(colon + 1) : docId;
            if (seg == null || seg.isEmpty()) return "文件夹";
            int slash = seg.lastIndexOf('/');
            return slash >= 0 ? seg.substring(slash + 1) : seg;
        } catch (Exception e) { return "文件夹"; }
    }

    /** 把文件/文件夹扫描结果并入全部歌曲，按统一身份去重（媒体库优先保留），再按时间倒序 */
    private void mergeAllSongs() {
        Set<String> seen = new HashSet<>();
        for (Song s : allSongs) seen.add(songKey(s));
        for (Song s : fileSongs) {
            if (seen.add(songKey(s))) allSongs.add(s);
        }
        sortByNewest(allSongs);
        txtCount.setText("共 " + allSongs.size() + " 首");
    }

    /** 按添加/修改时间倒序：最新歌曲在最顶端（时间未知的排最后） */
    private void sortByNewest(ArrayList<Song> list) {
        list.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
    }

    private long maxLastModified(ArrayList<Song> g) {
        long m = 0;
        for (Song s : g) if (s.lastModified > m) m = s.lastModified;
        return m;
    }

    private void showAddMenu() {
        PopupMenu pm = new PopupMenu(this, btnAdd);
        pm.getMenu().add(0, 1, 0, "添加音频文件…");
        pm.getMenu().add(0, 2, 0, "添加文件夹…");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) pickFiles(); else pickTree();
            return true;
        });
        pm.show();
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        // 读写权限：读用于播放，写用于日后删除该文件
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK); }
        catch (Exception e) { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show(); }
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_TREE); }
        catch (Exception e) { Toast.makeText(this, "无法打开文件夹选择器", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        // 注意：REQ_DELETE（媒体库删除系统确认框）只返回 resultCode，data 恒为 null，
        // 不能在此统一判 data==null，否则删除确认后永远走不到移除逻辑（列表不刷新）
        if (req == REQ_DELETE) {
            if (pendingDeleteKey != null) {
                removeSongByKey(pendingDeleteKey);
                pendingDeleteKey = null;
            }
            return;
        }
        if (data == null) return;
        if (req == REQ_PICK) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (pendingReplay != null) {
                // 授权失效重选：替换旧条目并立即播放新选择的文件
                Uri old = pendingReplay;
                pendingReplay = null;
                if (!uris.isEmpty()) {
                    takePersistableUriPermission(uris.get(0), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    savedFiles.remove(old);
                    savedFiles.add(uris.get(0));
                    saveUris(KEY_FILES, savedFiles);
                    String name = queryName(uris.get(0));
                    Song replay = new Song(name != null ? name : "文件", "文件", 0,
                            resolveFileRealPath(uris.get(0), name), uris.get(0), 0);
                    replay.source = 1;
                    for (java.util.Iterator<Song> it = fileSongs.iterator(); it.hasNext(); ) {
                        Song x = it.next();
                        if (x.uri != null && x.uri.equals(old)) it.remove();
                    }
                    fileSongs.add(replay);
                    afterExternalAdd();
                    playIndexForSong(replay);
                }
                return;
            }
            addFiles(uris);
        } else if (req == REQ_TREE) {
            Uri tree = data.getData();
            // 已导入的文件夹：不重复添加，改为刷新
            if (savedTrees.contains(tree)) {
                Toast.makeText(this, "该文件夹已导入，正在刷新…", Toast.LENGTH_SHORT).show();
                rescanTrees();
                return;
            }
            takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            savedTrees.add(tree);
            saveUris(KEY_TREES, savedTrees);
            scanTree(tree, treeLabelOf(tree));
            afterExternalAdd();
        }
    }

    private void addFiles(ArrayList<Uri> uris) {
        int added = 0, dup = 0;
        for (Uri u : uris) {
            takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (!savedFiles.contains(u)) savedFiles.add(u);
            String name = queryName(u);
            if (name != null) {
                Song song = new Song(name, "文件", 0, resolveFileRealPath(u, name), u, 0);
                song.source = 1;
                if (songExistsInFileSongs(song)) { dup++; continue; }
                fileSongs.add(song);
                added++;
            }
        }
        saveUris(KEY_FILES, savedFiles);
        if (added > 0) afterExternalAdd();
        else if (dup > 0) Toast.makeText(this, "所选文件已在列表中（共跳过 " + dup + " 个）", Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, "未添加任何文件", Toast.LENGTH_SHORT).show();
    }

    /** 单文件（SAF uri）尝试解析真实路径：按 文件名+大小 匹配媒体库，与文件夹扫描身份统一 */
    private String resolveFileRealPath(Uri u, String name) {
        if (name == null) return null;
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) size = c.getLong(0);
        } catch (Exception ignored) {}
        try {
            String sel = MediaStore.Audio.Media.DISPLAY_NAME + "=?";
            java.util.ArrayList<String> args = new java.util.ArrayList<>();
            args.add(name);
            if (size > 0) { sel += " AND " + MediaStore.Audio.Media.SIZE + "=?"; args.add(String.valueOf(size)); }
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.DATA}, sel, args.toArray(new String[0]), null)) {
                if (c != null && c.moveToFirst()) {
                    String p = c.getString(0);
                    if (p != null) return p;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 刷新所有已授权文件夹：增量合并（新增加入、已消失的移除），不重复添加 */
    private void rescanTrees() {
        Set<String> existing = new HashSet<>();
        for (Song s : fileSongs) {
            if (s.source != 2) existing.add(songKey(s));
        }
        // 收集现有文件夹歌（保留 key 用于判断消失）
        Set<String> beforeKeys = new HashSet<>();
        for (Song s : fileSongs) if (s.source == 2) beforeKeys.add(songKey(s));
        fileSongs.removeIf(x -> x.source == 2);
        int added = 0, gone = 0;
        for (Uri tree : savedTrees) {
            scanTree(tree, treeLabelOf(tree));
        }
        Set<String> seen = new HashSet<>(existing);
        for (Song s : fileSongs) {
            if (s.source == 2 && seen.add(songKey(s))) added++;
        }
        for (String k : beforeKeys) if (!seen.contains(k)) gone++;
        mergeAllSongs();
        rebuildLikedSongs();
        refreshViews();
        Toast.makeText(this, "刷新完成：新增 " + added + " 首" + (gone > 0 ? "，移除 " + gone + " 首已不存在的" : ""), Toast.LENGTH_SHORT).show();
    }

    private void refreshViews() {
        txtCount.setText("共 " + allSongs.size() + " 首");
        emptyView.setVisibility(allSongs.isEmpty() && fileSongs.isEmpty() ? View.VISIBLE : View.GONE);
        // 按当前视图状态精确恢复，刷新后不跳页
        switch (viewState) {
            case VIEW_ALBUMS: showAlbums(); break;
            case VIEW_ALL: showAllSongs(); break;
            case VIEW_FILES: showFiles(); break;
            case VIEW_LIKES: showLikes(); break;
            case VIEW_DONE: showDone(); break;
            case VIEW_ALBUM_SONGS:
                if (currentAlbum != null) showAlbumSongs(currentAlbum);
                break;
        }
    }

    // ---------- 删除歌曲 ----------

    private void deleteSong(Song s) {
        // 媒体库歌曲（Android 11+）：走系统确认框物理删除
        if (Build.VERSION.SDK_INT >= 30 && s.source == 0 && s.uri != null) {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                uris.add(s.uri);
                android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                pendingDeleteKey = songKey(s);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                return;   // 结果在 onActivityResult(REQ_DELETE) 处理
            } catch (Exception e) {
                Toast.makeText(this, "删除请求失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        // SAF 文件（单文件/文件夹扫描）：通过 DocumentsContract 删除文档
        boolean ok = false;
        if (s.uri != null) {
            try { ok = DocumentsContract.deleteDocument(getContentResolver(), s.uri); }
            catch (Exception e) { ok = false; }
        }
        if (!ok && s.path != null) {
            try { ok = new File(s.path).delete(); } catch (Exception e) { ok = false; }
        }
        removeSongEverywhere(s);
        Toast.makeText(this, ok ? "已删除「" + s.title + "」" : "删除失败（无写入权限，请到系统文件管理器删除）", Toast.LENGTH_LONG).show();
    }

    /** 从全部歌曲/文件/喜欢三处移除某首歌（按统一身份），并持久化 */
    private void removeSongEverywhere(Song s) {
        removeSongByKey(songKey(s));
        if (s.uri != null) {
            savedFiles.remove(s.uri);
            saveUris(KEY_FILES, savedFiles);
        }
    }

    private void removeSongByKey(String k) {
        fileSongs.removeIf(x -> songKey(x).equals(k));
        allSongs.removeIf(x -> songKey(x).equals(k));
        likedSet.remove(k);
        prefs.edit().putStringSet(KEY_LIKED, likedSet).apply();
        rebuildLikedSongs();
        // 同时清理该歌曲的「生成完毕」记录
        settings.removeDoneRecord(k);
        refreshViews();
    }

    /** 授权失效/无法访问的文件：立即从列表移除并持久化（用户要求误点击后不再残留） */
    private void removeInvalidFile(Song s) {
        String k = songKey(s);
        // 若是文件夹扫描的歌：整个文件夹授权一并失效，移除该文件夹及其中所有歌
        if (s.treeUri != null) {
            String tk = s.treeUri.toString();
            java.util.ArrayList<String> goneKeys = new java.util.ArrayList<>();
            for (Song x : fileSongs) {
                if (x.source == 2 && x.treeUri != null && x.treeUri.toString().equals(tk)) goneKeys.add(songKey(x));
            }
            savedTrees.remove(s.treeUri);
            saveUris(KEY_TREES, savedTrees);
            fileSongs.removeIf(x -> x.source == 2 && x.treeUri != null && x.treeUri.toString().equals(tk));
            allSongs.removeIf(x -> x.treeUri != null && x.treeUri.toString().equals(tk));
            for (String gk : goneKeys) likedSet.remove(gk);
            prefs.edit().putStringSet(KEY_LIKED, likedSet).apply();
            rebuildLikedSongs();
            refreshViews();
            Toast.makeText(this, "文件夹访问授权已失效，已从列表移除", Toast.LENGTH_LONG).show();
            return;
        }
        fileSongs.removeIf(x -> songKey(x).equals(k));
        if (s.uri != null) {
            savedFiles.remove(s.uri);
            saveUris(KEY_FILES, savedFiles);
        }
        allSongs.removeIf(x -> songKey(x).equals(k));
        likedSet.remove(k);
        prefs.edit().putStringSet(KEY_LIKED, likedSet).apply();
        rebuildLikedSongs();
        refreshViews();
        Toast.makeText(this, "「" + s.title + "」访问授权已失效，已从列表移除", Toast.LENGTH_LONG).show();
    }

    private void afterExternalAdd() {
        mergeAllSongs();
        emptyView.setVisibility(allSongs.isEmpty() && fileSongs.isEmpty() ? View.VISIBLE : View.GONE);
        if (fileSongs.isEmpty()) {
            Toast.makeText(this, "该位置没有找到音频文件", Toast.LENGTH_SHORT).show();
        } else {
            showFiles();
            Toast.makeText(this, "已添加 " + fileSongs.size() + " 个音频", Toast.LENGTH_SHORT).show();
        }
    }

    private void scanTree(Uri tree, String label) {
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String realPath = treeRealPath(tree);
            scanDocId(tree, rootId, label, realPath);
        } catch (Exception ignored) {}
    }

    /** primary 卷 → /storage/emulated/0；SD 卡 → /storage/卷名 */
    private String treeRealPath(Uri tree) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            int colon = docId.indexOf(':');
            String vol = colon >= 0 ? docId.substring(0, colon) : docId;
            return vol.equals("primary") ? "/storage/emulated/0" : "/storage/" + vol;
        } catch (Exception e) { return null; }
    }

    private void scanDocId(Uri tree, String docId, String label, String treePath) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try (Cursor c = getContentResolver().query(childrenUri, cols, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long lm = 0;
                    try { lm = c.getLong(3); } catch (Exception ignored) {}
                    if (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                        scanDocId(tree, id, label, treePath);
                    } else if (isAudio(name)) {
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        // 尝试解析真实路径：docId 形如 "primary:Download/Telegram/x.mp3" 或 "Download/x.mp3"
                        String real = null;
                        if (treePath != null) {
                            String rel = id;
                            int colon = id.indexOf(':');
                            if (colon >= 0) rel = id.substring(colon + 1);
                            if (rel != null && !rel.isEmpty()) real = treePath + "/" + rel;
                        }
                        Song s = new Song(name, label, 0, real, docUri, 0);
                        s.source = 2;
                        s.treeUri = tree;
                        s.treeLabel = label;
                        s.lastModified = lm;
                        fileSongs.add(s);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isAudio(String n) {
        if (n == null) return false;
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".mp3") || l.endsWith(".flac") || l.endsWith(".wav")
                || l.endsWith(".m4a") || l.endsWith(".aac") || l.endsWith(".ogg")
                || l.endsWith(".opus") || l.endsWith(".mid") || l.endsWith(".amr");
    }

    private String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        String last = u.getLastPathSegment();
        return last != null ? last : "未知文件";
    }

    private void takePersistableUriPermission(Uri u, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(u, flags);
        } catch (Exception ignored) {}
    }

    private void saveUris(String key, ArrayList<Uri> uris) {
        Set<String> set = new HashSet<>();
        for (Uri u : uris) set.add(u.toString());
        prefs.edit().putStringSet(key, set).apply();
    }

    // ---------- 视图切换 ----------

    private static final int VIEW_ALBUMS = 0, VIEW_ALL = 1, VIEW_FILES = 2, VIEW_LIKES = 3, VIEW_ALBUM_SONGS = 4, VIEW_DONE = 5;
    private int viewState = VIEW_ALBUMS;
    private Album currentAlbum;   // VIEW_ALBUM_SONGS 时的专辑

    private void showAlbums() {
        viewState = VIEW_ALBUMS;
        gridPane.setVisibility(View.VISIBLE);
        listPane.setVisibility(View.GONE);
        setTab(TAB_ALBUMS);
        gridBox.setAdapter(new AlbumAdapter());
    }

    private void showAllSongs() {
        viewState = VIEW_ALL;
        shownSongs.clear();
        shownSongs.addAll(allSongs);
        showList("全部歌曲 · " + shownSongs.size() + " 首", TAB_ALL);
    }

    private void showAlbumSongs(Album a) {
        viewState = VIEW_ALBUM_SONGS;
        currentAlbum = a;
        shownSongs.clear();
        for (Song s : allSongs) if (s.albumId == a.id) shownSongs.add(s);
        showList(a.name + " · " + shownSongs.size() + " 首", TAB_ALBUMS);
    }

    private void showFiles() {
        viewState = VIEW_FILES;
        gridPane.setVisibility(View.GONE);
        listPane.setVisibility(View.VISIBLE);
        setTab(TAB_FILES);
        listHeader.setText("我的文件 · " + fileSongs.size() + " 首");
        // 分组：文件夹（按 tree）在前，单独添加的文件归「文件」组；组间/组内均按最新时间倒序
        groups.clear();
        groupLabels.clear();
        java.util.LinkedHashMap<Uri, ArrayList<Song>> byTree = new java.util.LinkedHashMap<>();
        ArrayList<Song> singles = new ArrayList<>();
        for (Song s : fileSongs) {
            if (s.source == 2 && s.treeUri != null) {
                byTree.computeIfAbsent(s.treeUri, k -> new ArrayList<>()).add(s);
            } else singles.add(s);
        }
        java.util.ArrayList<java.util.Map.Entry<Uri, ArrayList<Song>>> entries =
                new java.util.ArrayList<>(byTree.entrySet());
        entries.sort((e1, e2) -> Long.compare(maxLastModified(e2.getValue()), maxLastModified(e1.getValue())));
        for (Map.Entry<Uri, ArrayList<Song>> e : entries) {
            ArrayList<Song> g = e.getValue();
            if (g.isEmpty()) continue;
            sortByNewest(g);
            groups.add(g);
            groupLabels.add(g.get(0).treeLabel != null ? g.get(0).treeLabel : "文件夹");
        }
        if (!singles.isEmpty()) {
            sortByNewest(singles);
            groups.add(singles);
            groupLabels.add("文件");
        }
        groupedMode = true;
        renderList();
    }

    /** 收藏/去重统一身份：真实路径优先（媒体库 DATA 与文件夹扫描解析出的路径一致），其次 URI */
    static String songKey(Song s) {
        if (s.path != null && !s.path.isEmpty()) return "path:" + s.path;
        if (s.uri != null) return "uri:" + s.uri.toString();
        return "path:";
    }

    private boolean isLiked(Song s) { return likedSet.contains(songKey(s)); }

    private void toggleLike(Song s) {
        String k = songKey(s);
        if (likedSet.contains(k)) likedSet.remove(k);
        else likedSet.add(k);
        prefs.edit().putStringSet(KEY_LIKED, likedSet).apply();
        // 若正在喜欢页，刷新列表；其他列表页直接重绘爱心状态
        if (viewState == VIEW_LIKES) showLikes();
        else if (listPane.getVisibility() == View.VISIBLE) renderList();
        Toast.makeText(this, likedSet.contains(k) ? "已添加到喜欢 ♥" : "已取消喜欢", Toast.LENGTH_SHORT).show();
    }

    /** 从全部歌曲 + 文件合并出收藏列表（文件已不存在的自动跳过） */
    private void loadLikedSongs() {
        likedSet.clear();
        Set<String> saved = prefs.getStringSet(KEY_LIKED, new HashSet<>());
        if (saved != null) likedSet.addAll(saved);
        rebuildLikedSongs();
    }

    private void rebuildLikedSongs() {
        likedSongs.clear();
        ArrayList<Song> pool = new ArrayList<>(allSongs);
        pool.addAll(fileSongs);
        Set<String> seen = new HashSet<>();
        for (Song s : pool) {
            String k = songKey(s);
            if (likedSet.contains(k) && !seen.contains(k)) {
                likedSongs.add(s);
                seen.add(k);
            }
        }
        sortByNewest(likedSongs);
    }

    private void showLikes() {
        viewState = VIEW_LIKES;
        rebuildLikedSongs();
        shownSongs.clear();
        shownSongs.addAll(likedSongs);
        showList("♥ 喜欢 · " + shownSongs.size() + " 首", TAB_LIKES);
    }

    /** 「已生成」tab：展示后台生成任务（进行中带进度条）+ 生成完毕的歌曲（长期保留缓存） */
    private void showDone() {
        viewState = VIEW_DONE;
        shownSongs.clear();
        shownSongs.addAll(scanDoneSongs());
        showList("已生成 · " + shownSongs.size() + " 首", TAB_DONE);
    }

    /** 扫描所有歌曲中已有「生成完毕」记录的歌（按完成时间倒序） */
    private ArrayList<Song> scanDoneSongs() {
        ArrayList<Song> out = new ArrayList<>();
        ArrayList<Song> pool = new ArrayList<>(allSongs);
        pool.addAll(fileSongs);
        Set<String> seen = new HashSet<>();
        for (Song s : pool) {
            String k = songKey(s);
            if (!seen.add(k)) continue;
            if (settings.getDoneTime(k) <= 0) continue;
            // 校验完整缓存确实存在（缓存被清理 / 旧版本 key 不匹配的记录自动移除，避免"显示但播放无字幕"）
            long dur = s.duration > 0 ? s.duration : 0;
            if (dur <= 0) { settings.removeDoneRecord(k); continue; }
            String id = s.path != null ? s.path : String.valueOf(s.uri);
            if (!subMgr.hasFullCache(SubtitleManager.audioKey(id, dur))) {
                settings.removeDoneRecord(k);
                continue;
            }
            out.add(s);
        }
        out.sort((a, b) -> Long.compare(settings.getDoneTime(songKey(b)), settings.getDoneTime(songKey(a))));
        return out;
    }

    private void showList(String header, int tab) {
        gridPane.setVisibility(View.GONE);
        listPane.setVisibility(View.VISIBLE);
        setTab(tab);
        listHeader.setText(header);
        groupedMode = false;
        groups.clear();
        groupLabels.clear();
        renderList();
    }

    private int currentTab = TAB_ALBUMS;
    private boolean groupedMode = false;
    private final ArrayList<String> groupLabels = new ArrayList<>();
    private final ArrayList<ArrayList<Song>> groups = new ArrayList<>();

    private void setTab(int tab) {
        currentTab = tab;
        btnAlbums.setBackgroundResource(tab == TAB_ALBUMS ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        btnAll.setBackgroundResource(tab == TAB_ALL ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        btnFiles.setBackgroundResource(tab == TAB_FILES ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        btnLikes.setBackgroundResource(tab == TAB_LIKES ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        btnDone.setBackgroundResource(tab == TAB_DONE ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
    }

    private void renderList() {
        listBox.removeAllViews();
        int hPad = (int) (6 * getResources().getDisplayMetrics().density);
        // 「已生成」页：顶部先渲染进行中的后台生成任务（进度条，含刚完成的「已完成」过渡卡），再渲染已完成歌曲
        if (viewState == VIEW_DONE) {
            for (BulkGenerator.Task t : bulkGen.getTasks()) {
                if ("failed".equals(t.status)) continue;
                addTaskCard(t, hPad);
            }
        }
        if (groupedMode) {
            // 分组渲染：文件夹 section 标题 + 组内歌曲
            for (int g = 0; g < groups.size(); g++) {
                TextView sec = new TextView(this);
                sec.setText("▸ " + groupLabels.get(g) + "（" + groups.get(g).size() + "）");
                sec.setTextSize(13);
                sec.setTextColor(0xFF8F8BFF);
                sec.setPadding(hPad + 6, (int) (14 * getResources().getDisplayMetrics().density), hPad, 6);
                sec.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                listBox.addView(sec);
                for (Song s : groups.get(g)) addSongRow(s);
            }
        } else {
            for (Song s : shownSongs) addSongRow(s);
        }
        // 空态提示：文件/喜欢标签下引导操作（媒体库无歌时同样显示）
        // 「已生成」页有进行中任务时（顶部已有任务卡）不显示空态
        boolean listEmpty = groupedMode ? groups.isEmpty() : shownSongs.isEmpty();
        if (listEmpty && !(viewState == VIEW_DONE && bulkGen.hasPending())) {
            TextView empty = new TextView(this);
            if (currentTab == TAB_FILES) {
                empty.setText("还没有添加文件。\n点右上角 ＋ →「添加音频文件…」或「添加文件夹…」，\n即可导入手机里的任意音频（含不在媒体库中的文件）。");
            } else if (currentTab == TAB_LIKES) {
                empty.setText("还没有喜欢的歌曲。\n在歌曲列表点右侧的 ♥ 即可收藏到这里。");
            } else if (currentTab == TAB_DONE) {
                empty.setText("还没有生成完毕的字幕。\n长按任意歌曲 →「后台生成字幕」，\n生成完成后会自动出现在这里（缓存长期保留）。");
            } else {
                empty.setText("本地媒体库中没有歌曲。\n可点右上角 ＋ 手动添加文件/文件夹。");
            }
            empty.setTextSize(14);
            empty.setTextColor(0xFF9A97AD);
            empty.setPadding(40, 60, 40, 20);
            empty.setGravity(android.view.Gravity.CENTER);
            listBox.addView(empty);
        }
    }

    /** 渲染后台生成任务卡（进行中任务 + 进度条 + 详情） */
    private void addTaskCard(BulkGenerator.Task t, int hPad) {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(10 * dp), (int)(8 * dp), (int)(10 * dp), (int)(8 * dp));
        card.setBackgroundResource(R.drawable.bg_player_bar);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(hPad, (int)(4 * dp), hPad, (int)(8 * dp));
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(t.song.title + "（" + t.engineLabel + "）");
        name.setTextSize(14);
        name.setTextColor(0xFFF0EFF5);
        name.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name);
        TextView status = new TextView(this);
        if ("done".equals(t.status)) {
            status.setText("已完成");
        } else if ("running".equals(t.status)) {
            status.setText(t.progress + "%");
        } else if ("failed".equals(t.status)) {
            status.setText("失败");
        } else {
            status.setText("排队中");
        }
        status.setTextSize(12);
        status.setTextColor(0xFFB79CFF);
        top.addView(status);
        TextView cancel = new TextView(this);
        cancel.setText(" 取消"); cancel.setTextSize(12); cancel.setTextColor(0xffff9a9a);
        cancel.setOnClickListener(v -> {
            bulkGen.cancelTask(t);
            Toast.makeText(this, "后台生成已暂停，已生成字幕保留", Toast.LENGTH_SHORT).show();
            renderList();
        });
        top.addView(cancel);
        card.addView(top);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress("done".equals(t.status) ? 100 : Math.max(1, Math.min(100, t.progress)));
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)(6 * dp));
        blp.topMargin = (int)(6 * dp);
        bar.setLayoutParams(blp);
        card.addView(bar);

        TextView detail = new TextView(this);
        detail.setText(t.detail == null ? "" : t.detail);
        detail.setTextSize(11);
        detail.setTextColor(0xFF9A97AD);
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = (int)(4 * dp);
        detail.setLayoutParams(dlp);
        card.addView(detail);
        listBox.addView(card);
    }

    /** 渲染单个歌曲行（封面/标题/爱心/点击播放/长按删除） */
    private void addSongRow(final Song s) {
        int hPad = (int) (6 * getResources().getDisplayMetrics().density);
        View row = getLayoutInflater().inflate(R.layout.item_song, listBox, false);
        row.setPadding(hPad, 0, hPad, 0);

        ImageView cover = row.findViewById(R.id.img_cover);
        TextView title = row.findViewById(R.id.txt_title);
        TextView artist = row.findViewById(R.id.txt_artist);
        TextView duration = row.findViewById(R.id.txt_duration);
        ImageView like = row.findViewById(R.id.img_like);

        title.setText(s.title);
        artist.setText(s.artist == null || s.artist.isEmpty() ? "未知艺术家" : s.artist);
        duration.setText(s.duration > 0 ? fmt(s.duration) : "");
        // 封面：优先专辑艺术图，其次音频内嵌封面（content URI）。失败自动回退默认图标
        AlbumArtLoader.load(this, s.albumId, artPathByAlbum.get(s.albumId), s.uri, cover);

        // 喜欢按钮：红心/灰心 + 点击切换
        like.setImageResource(isLiked(s) ? R.drawable.ic_heart_on : R.drawable.ic_heart_off);
        like.setOnClickListener(v -> toggleLike(s));

        // 已生成完毕（有完整字幕缓存）的歌：标题旁打「字幕✓」标志
        TextView doneBadge = row.findViewById(R.id.txt_done_badge);
        doneBadge.setVisibility(settings.getDoneTime(songKey(s)) > 0 ? View.VISIBLE : View.GONE);

        boolean isCurrent = currentSong != null && sameSong(s, currentSong);
        row.setBackgroundResource(isCurrent ? R.drawable.bg_song_playing : android.R.color.transparent);
        title.setTypeface(android.graphics.Typeface.DEFAULT,
                isCurrent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        row.setOnClickListener(v -> playIndexForSong(s));
        // 长按：弹菜单（后台生成字幕 / 删除歌曲）
        row.setOnLongClickListener(v -> {
            showSongMenu(s);
            return true;
        });
        listBox.addView(row);
    }

    /** 长按歌曲菜单 */
    private void showSongMenu(final Song s) {
        if (viewState == VIEW_DONE) {
            // 已生成栏：歌都已生成完毕，不提供「后台生成」防止套娃；删除 = 只删字幕缓存，不删音乐
            new AlertDialog.Builder(this)
                    .setTitle(s.title)
                    .setItems(new String[]{"删除缓存"}, (d, w) -> deleteGeneratedCache(s))
                    .show();
            return;
        }
        // 已生成栏外的歌：菜单按「是否有后台生成产物」区分——
        //  - 有产物（在已生成栏）：不提供「删除字幕缓存」，避免误删后台生成的正式字幕；要重新生成请到已生成栏删缓存
        //  - 无产物：提供「删除字幕缓存」，只删播放时前台产生的字幕/翻译缓存，不影响任何产物
        final boolean hasDone = settings.getDoneTime(songKey(s)) > 0;
        final String[] items = hasDone
                ? new String[]{"后台生成字幕", "删除歌曲"}
                : new String[]{"后台生成字幕", "删除字幕缓存", "删除歌曲"};
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setItems(items, (d, w) -> {
                    if (w == 0) chooseGenEngine(s);
                    else if (w == 1 && !hasDone) deleteSubtitleCache(s);
                    else deleteSong(s);   // 长按 → 点删除 → 直接删除（不再弹应用内确认框；媒体库歌曲仍会走系统删除确认）
                })
                .show();
    }

    /** 非已生成栏「删除字幕缓存」：只删该歌播放时前台产生的字幕/翻译缓存（seg_X 等），
     *  绝不动后台生成产物（done 记录 / 已生成栏）。删完下次播放会重新生成字幕。
     *  仅在无后台生成产物的歌上提供（有产物的歌入口已在菜单中隐藏）。 */
    private void deleteSubtitleCache(Song s) {
        long dur = s.duration > 0 ? s.duration : (player != null ? (long) player.getDuration() : 0);
        String id = s.path != null ? s.path : String.valueOf(s.uri);
        String cacheKey = SubtitleManager.audioKey(id, dur);
        subMgr.clearCache(cacheKey);
        // 不删 done 记录：后台生成产物与播放缓存是两回事，此操作只清播放缓存
        if (s == currentSong && player != null) {
            // 用户要求：删缓存后强制从头播放。seek 回 0 并按当前位置重开字幕管线（playSeg=0），
            // 分片 0 就绪后自动从头播（暂停中走 maybeAutoStartPlayback；播放中清 wait 标志直接从头继续）
            player.seekTo(0);
            setupSubtitle(s, player.getDuration());
            if (player.isPlaying()) {
                waitSubtitleStart = false;
                waitSubKey = null;
                waitSeg = -1;
            }
        } else if (s == currentSong) {
            subKey = null;
            subLines.clear();
            subtitleArea.setVisibility(View.GONE);
            txtEngineBadge.setVisibility(View.GONE);
            progressView.setVisibility(View.VISIBLE);
            txtSubStatus.setVisibility(View.VISIBLE);
        }
        renderList();
        Toast.makeText(this, "已删除「" + s.title + "」的字幕/翻译缓存（音乐保留，下次播放重新生成）", Toast.LENGTH_SHORT).show();
    }

    /** 已生成栏删除：只删除该歌后台生成的字幕缓存与完成记录，音乐文件本身保留 */
    private void deleteGeneratedCache(Song s) {        long dur = s.duration > 0 ? s.duration : (player != null ? (long) player.getDuration() : 0);
        String id = s.path != null ? s.path : String.valueOf(s.uri);
        String cacheKey = SubtitleManager.audioKey(id, dur);
        subMgr.clearCache(cacheKey);
        settings.removeDoneRecord(songKey(s));
        // 若正在播放该歌：强制从头播放并重新生成字幕（删缓存 = 从头来）
        if (s == currentSong && player != null) {
            player.seekTo(0);
            setupSubtitle(s, player.getDuration());
            if (player.isPlaying()) {
                waitSubtitleStart = false;
                waitSubKey = null;
                waitSeg = -1;
            }
        } else if (s == currentSong) {
            subKey = null;
            subLines.clear();
            subtitleArea.setVisibility(View.GONE);
            txtEngineBadge.setVisibility(View.GONE);
            progressView.setVisibility(View.VISIBLE);
            txtSubStatus.setVisibility(View.VISIBLE);
        }
        if (viewState == VIEW_DONE) showDone();
        else renderList();   // 其他栏：刷新列表让「字幕✓」标志随完成记录一起消失
        Toast.makeText(this, "已删除「" + s.title + "」的字幕缓存（音乐文件保留）", Toast.LENGTH_SHORT).show();
    }

    /** 后台生成：先让用户选择引擎（离线 / 在线） */
    private void chooseGenEngine(final Song s) {
        // 已生成完毕的歌（有 done 记录 + 完整缓存）不允许重复生成，避免秒完成/浪费算力
        if (settings.getDoneTime(MainActivity.songKey(s)) > 0) {
            Toast.makeText(this, "该歌曲已生成完毕（完整字幕已在缓存中），无需重复生成", Toast.LENGTH_LONG).show();
            return;
        }
        String cloudVendor;
        switch (settings.getCloudAsrEngine()) {
            case "alibaba": cloudVendor = "阿里云"; break;
            case "iflytek": cloudVendor = "讯飞"; break;
            default: cloudVendor = "腾讯云"; break;
        }
        boolean cloudReady = settings.isCloudAsrEnabled() && settings.isCloudAsrConfigured();
        final String[] engines = cloudReady
                ? new String[]{"离线 SenseVoice（免费/快）", "离线 Whisper（抗噪强）", cloudVendor + "（联网，按音频时长计费）"}
                : new String[]{"离线 SenseVoice（免费/快）", "离线 Whisper（抗噪强）", cloudVendor + "（未开启/密钥未填，可先到设置开启）"};
        new AlertDialog.Builder(this)
                .setTitle("选择生成引擎（" + s.title + "）")
                .setSingleChoiceItems(engines, 0, null)
                .setPositiveButton("开始生成", (d, w) -> {
                    int sel = ((android.app.AlertDialog) d).getListView().getCheckedItemPosition();
                    if (sel < 0) sel = 0;
                    String key = sel == 1 ? "whisper" : sel == 2 ? "cloud" : "sensevoice";
                    if ("cloud".equals(key) && !(settings.isCloudAsrEnabled() && settings.isCloudAsrConfigured())) {
                        Toast.makeText(this, "联网 ASR 未开启或密钥未填全，请先到设置中开启", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (bulkGen.isQueued(MainActivity.songKey(s))) {
                        Toast.makeText(this, "该歌曲已在生成队列中", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 正在前台播放并生成字幕的同一首歌：不重复加入后台（避免双线程同写缓存）
                    if (currentSong != null && sameSong(s, currentSong) && subKey != null) {
                        Toast.makeText(this, "该歌曲正在前台播放并生成字幕中，无需后台生成", Toast.LENGTH_LONG).show();
                        return;
                    }
                    bulkGen.enqueue(s, key);
                    Toast.makeText(this, "已加入后台生成（「已生成」页可查看进度）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String artistName(Song s) {
        if (s.artist == null || s.artist.isEmpty()) return "未知艺术家";
        return s.artist;
    }

    /** 判断两首歌是否为同一音频（统一身份比较） */
    private boolean sameSong(Song a, Song b) {
        if (a == b) return true;
        return songKey(a).equals(songKey(b));
    }

    // ---------- 播放控制 ----------

    private void playIndex(int idx) {
        if (shownSongs.isEmpty()) return;
        current = Math.floorMod(idx, shownSongs.size());
        playIndexForSong(shownSongs.get(current));
    }

    private void playIndexForSong(Song s) {
        if (playback.song != null && sameSong(s, playback.song) && (playback.isPlaying() || playback.preparing)) return;
        playback.setQueue(groupedMode ? flattenGroups() : shownSongs, playMode);
        player = playback;
        playback.play(s);
        // play() 同步更新 PlaybackSession.song；立即重绘可避免异步准备音频期间保留旧歌曲高光。
        currentSong = playback.song;
        renderedHighlightKey = currentSong == null ? null : songKey(currentSong);
        if (listPane != null && listPane.getVisibility() == View.VISIBLE) renderList();
    }

    @Override public void onPlaybackChanged() {
        if (isDestroyed() || playback == null) return;
        currentSong = playback.song;
        if (currentSong == null) return;
        player = playback;
        String highlightKey = songKey(currentSong);
        if (!highlightKey.equals(renderedHighlightKey)) {
            renderedHighlightKey = highlightKey;
            if (listPane != null && listPane.getVisibility() == View.VISIBLE) renderList();
        }
        if (txtHeaderTitle != null) {
            txtHeaderTitle.setText(currentSong.title == null || currentSong.title.isEmpty() ? "音声播放器" : currentSong.title);
            txtHeaderTitle.setSelected(true);
        }
        txtNowTitle.setText(currentSong.title);
        txtNowArtist.setText(artistName(currentSong));
        btnPlay.setImageResource(player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        String key = songKey(currentSong) + "|" + player.getDuration();
        if (!key.equals(displayedSong)) {
            displayedSong = key;
            imgNowCover.setImageResource(R.drawable.ic_album_default);
            AlbumArtLoader.load(this, currentSong.albumId, artPathByAlbum.get(currentSong.albumId), currentSong.uri, imgNowCover);
            setupSubtitle(currentSong, player.getDuration());
        }
        seekBar.setMax(player.getDuration());
        handler.removeCallbacks(ticker); handler.post(ticker);
        if (playback.preparing) txtSubStatus.setText("正在加载音频…");
    }
    @Override public void onPlaybackError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String sanitizeName(String n) {
        return n == null ? "audio" : n.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_").substring(0, Math.min(24, n.length()));
    }

    /** 设置播放数据源：优先 content://（Android 10+ 推荐，仅需媒体权限，路径访问在 Android 13+ 会被拒）；
     *  其次文件路径；最后 SAF 持久 URI 的 FileDescriptor */
    /** 分组模式下的扁平播放池（文件夹 + 单文件） */
    private ArrayList<Song> flattenGroups() {
        ArrayList<Song> pool = new ArrayList<>();
        for (ArrayList<Song> g : groups) pool.addAll(g);
        return pool;
    }

    private void togglePlay() {
        // 首次加载字幕等待期：不响应播放/暂停点击，避免打断"等字幕就绪自动播放"
        if (waitSubtitleStart) {
            Toast.makeText(this, "字幕生成中，就绪后自动播放", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player == null) {
            if (current < 0) {
                if (!shownSongs.isEmpty()) playIndex(0);
                else if (groupedMode && !flattenGroups().isEmpty()) playIndexForSong(flattenGroups().get(0));
            }
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
            pipeline.pause();   // 播放暂停 → 暂停 AI 推理（控温）
        } else {
            player.start();
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
            pipeline.resume();  // 恢复播放 → 恢复推理
        }
    }

    private void cycleMode() {
        playMode = (playMode + 1) % 3;
        playback.setMode(playMode);
        btnMode.setText(playMode == MODE_SINGLE ? "单曲" : playMode == MODE_SHUFFLE ? "随机" : "顺序");
        Toast.makeText(this, "播放模式：" + btnMode.getText(), Toast.LENGTH_SHORT).show();
    }

    // ---------- 字幕系统 ----------

    /** 播放某首歌时初始化字幕（缓存命中直接加载，否则启动增量管线）。
     *  返回 true 表示"无可用字幕缓存，需等待字幕就绪后再开始播放"（首次加载先暂停） */
    private boolean setupSubtitle(Song s, int durationMs) {
        waitSubtitleStart = false; waitSubKey = null; waitSeg = -1;
        subLines.clear(); processedSegs.clear(); noSpeechSegs.clear(); cloudSegs.clear();
        if (txtEngineBadge != null) txtEngineBadge.setVisibility(View.GONE);
        if (durationMs <= 0) return false;
        playback.configureSubtitles();
        subKey = playback.audioKey;
        if (subKey == null) return false;
        subDuration = durationMs; subTotalSegs = subMgr.segmentCount(subDuration);

        subOffsetMs = settings.getSubOffsetMs();
        for (int seg : subMgr.getProcessedSegments(subKey)) { processedSegs.add(seg); subLines.addAll(subMgr.loadSegment(subKey, seg)); }
        noSpeechSegs.addAll(subMgr.getNoSpeechSegments(subKey));
        cloudSegs.addAll(subMgr.getCloudSegments(subKey));
        sortSubLines();
        fullCachePlayback = subMgr.isAllDone(subKey, subTotalSegs);
        subtitleArea.setVisibility(settings.isSubtitleEnabled() ? View.VISIBLE : View.GONE);
        btnSubtitle.setVisibility(View.VISIBLE); btnTrans.setVisibility(View.VISIBLE);
        btnComplete.setVisibility(fullCachePlayback ? View.GONE : View.VISIBLE);
        progressView.setVisibility(View.VISIBLE); txtSubStatus.setVisibility(View.VISIBLE);
        txtSubStatus.setText(fullCachePlayback ? "字幕已生成" : "字幕后台生成，音频可正常播放");
        return false;
    }

    private void toggleSubtitle() {
        settings.setSubtitleEnabled(!settings.isSubtitleEnabled());
        playback.configureSubtitles();
        refreshSubtitleButton();
        if (currentSong != null && player != null) setupSubtitle(currentSong, player.getDuration());
        subtitleArea.setVisibility(settings.isSubtitleEnabled() ? View.VISIBLE : View.GONE);
    }

    private void refreshSubtitleButton() {
        boolean on = settings.isSubtitleEnabled();
        btnSubtitle.setText(on ? "字幕·开" : "字幕");
        btnSubtitle.setBackgroundResource(on ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
    }

    /** 翻译开关（独立于字幕）：字幕识别是本地免费，翻译消耗所选引擎的额度/Key，需用户主动开启。
     *  开启后从当前播放句所在分片开始补译（先译正在听的，再向后，最后回补前面）；
     *  播放回退到前面未翻译的句子时（重播开头等），翻译开着会自动接着补译；
     *  已翻译的句子写入缓存，重放不重复消耗额度 */
    private void toggleTranslation() {
        boolean on = !settings.isTranslationEnabled();
        if (on && !transConfigured()) {
            String hint = "请在设置中填写所选翻译引擎的密钥后再开启翻译";
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            return;
        }
        settings.setTranslationEnabled(on);
        refreshTransButton();
        refreshFullTransButton();
        Toast.makeText(this, on ? "翻译已开启，从当前句子开始补译…" : "翻译已关闭，不再消耗翻译额度", Toast.LENGTH_SHORT).show();
        if (on) {
            pipeline.retryTranslation();
            int fromSeg = 0;
            if (player != null) {
                fromSeg = (int) (player.getCurrentPosition() / subMgr.getSegmentMs());
            }
            pipeline.requestTranslationBackfill(fromSeg);
        }
    }

    /** 所选翻译引擎是否已配置密钥 */
    private boolean transConfigured() { return settings.isTranslationConfigured(); }

    private void refreshTransButton() {
        boolean on = settings.isTranslationEnabled();
        btnTrans.setText(on ? "翻译·开" : "翻译");
        btnTrans.setBackgroundResource(on ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
    }

    private void refreshFullTransButton() {
        if (fullTrans == null) return;
        boolean on = settings.isTranslationEnabled();
        fullTrans.setText(on ? "翻译·开" : "翻译");
        fullTrans.setBackgroundResource(on ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
    }

    /** 播放位置推进：更新字幕行 + 增量目标（支持时间偏移校准与行间间隙延续，避免闪空） */
    private void updateSubtitle(int posMs) {
        if (subKey == null || subLines == null) return;
        // 完整缓存/后台生成中的歌：字幕常显，不受字幕开关限制；普通增量字幕仍受开关控制
        if (!settings.isSubtitleEnabled() && !fullCachePlayback) return;
        int matchPos = posMs - subOffsetMs;   // 偏移校准：蓝牙等输出延迟
        int seg = (int) (matchPos / subMgr.getSegmentMs());
        pipeline.updateTarget(seg);

        SubtitleManager.SubtitleLine prev = null;   // 上一句（用于行间间隙延续）
        boolean found = false;
        for (SubtitleManager.SubtitleLine l : subLines) {
            if (matchPos >= l.startMs && matchPos < l.endMs) {
                showSubLine(l);
                // 自动补译：当前句没有译文 + 翻译开关打开 → 从该句所在分片开始补译
                // （重播到开头等场景：前面未翻译的句子会在这里被检测到并接着翻译；
                //  pipeline 内部有防重入，正在补译时不重复启动；已翻译的句子直接跳过）
                if (settings.isTranslationEnabled() && !l.noSpeech
                        && l.translated.isEmpty() && !l.text.isEmpty()) {
                    pipeline.requestTranslationBackfill(seg);
                }
                found = true;
                break;
            }
            if (matchPos < l.startMs) break;   // subLines 按 startMs 升序
            prev = l;
        }
        if (!found) {
            if (prev != null && !prev.noSpeech) {
                // 句子间隙（识别漏句/停顿/标点间隔）：上一句高亮一直保持到下一句出现，
                // 不闪空、不限制时长（ASMR 长停顿也保持）
                showSubLine(prev);
            } else if (processedSegs.contains(seg) && noSpeechSegs.contains(seg)) {
                txtSubOrigin.setText("");
                txtSubTrans.setText("");
                txtSubNoSpeech.setVisibility(View.VISIBLE);
            } else if (!processedSegs.contains(seg)) {
                txtSubOrigin.setText("");
                txtSubTrans.setText("");
                txtSubNoSpeech.setVisibility(View.GONE);
                if (txtSubStatus.getText().length() == 0 || txtSubStatus.getText().toString().contains("未生成")) {
                    txtSubStatus.setText("该区间字幕尚未生成，请顺序播放或等待后台处理");
                }
            } else {
                // 该分片已处理且有语音，但当前没有字幕行匹配（如纯音乐前奏/识别漏句）：
                // 清空字幕区，不能残留上一句
                txtSubOrigin.setText("");
                txtSubTrans.setText("");
                txtSubNoSpeech.setVisibility(View.GONE);
            }
        }
        progressView.setData(subDuration, subTotalSegs, processedSegs, noSpeechSegs, cloudSegs, subCurrentSeg, subProgress);
    }

    /** 显示一条字幕（处理"未检测到人声"与空文本） */
    private void showSubLine(SubtitleManager.SubtitleLine l) {
        if (l.noSpeech) {
            txtSubOrigin.setText("");
            txtSubTrans.setText("");
            txtSubNoSpeech.setVisibility(View.VISIBLE);
            return;
        }
        txtSubOrigin.setText(l.text.isEmpty() ? "…" : l.text);
        txtSubTrans.setText(l.translated);
        txtSubNoSpeech.setVisibility(View.GONE);
    }

    /** 拖动进度到某位置：未来未处理分片给提示 */
    private void onSeekTo(int posMs) {
        if (player == null) return;
        player.seekTo(posMs);
        waitSubtitleStart = false; waitSubKey = null; waitSeg = -1;
        updateSubtitle(posMs);
        if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE) updateFullSubtitle(posMs);
    }

    /** 当前已完全生成完毕的最大分片号（-1 = 尚无分片生成完） */
    private int maxProcessedSeg() {
        int max = -1;
        for (int s : processedSegs) if (s > max) max = s;
        return max;
    }

    private void sortSubLines() {
        Collections.sort(subLines, new Comparator<SubtitleManager.SubtitleLine>() {
            @Override public int compare(SubtitleManager.SubtitleLine a, SubtitleManager.SubtitleLine b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        dedupeSubLines();   // 排序后合并相邻重复句（分片边界同一句被识别两次时防止字幕重复/慢一行）
    }

    /** 合并相邻的重复字幕行：文本归一后相同、且时间间隔 < 3 秒 → 判为同一句被重复识别/复读，
     *  合并为一行（时间取并集、译文优先保留非空），避免字幕列表多出一行导致后面全部错位。 */
    private void dedupeSubLines() {
        if (subLines.size() < 2) return;
        java.util.ArrayList<SubtitleManager.SubtitleLine> out = new java.util.ArrayList<>();
        for (SubtitleManager.SubtitleLine l : subLines) {
            if (!out.isEmpty()) {
                SubtitleManager.SubtitleLine prev = out.get(out.size() - 1);
                if (!prev.noSpeech && !l.noSpeech && similarDup(prev, l)) {
                    prev.endMs = Math.max(prev.endMs, l.endMs);
                    if ((prev.translated == null || prev.translated.isEmpty())
                            && l.translated != null && !l.translated.isEmpty()) {
                        prev.translated = l.translated;
                    }
                    continue;   // 丢弃后一行（已并入前一行）
                }
            }
            out.add(l);
        }
        if (out.size() != subLines.size()) {
            subLines.clear();
            subLines.addAll(out);
        }
    }

    private boolean similarDup(SubtitleManager.SubtitleLine a, SubtitleManager.SubtitleLine b) {
        String ta = normText(a.text), tb = normText(b.text);
        if (ta.isEmpty() || !ta.equals(tb)) return false;
        long gap = b.startMs - a.endMs;
        return gap < 3000;   // 3 秒内再次出现相同文本 → 重复
    }

    /** 归一化：去空白与标点，仅比较文字本身（忽略识别时空格/标点的细微差异） */
    private String normText(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}。、，！？…·〜〜「」『』（）()…]", "");
    }

    // ---------- 全屏滚动字幕页（点击字幕区进入，类似网易云滚动歌词） ----------

    private void ensureFullOverlay() {
        if (fullOverlay != null) return;
        fullOverlay = LayoutInflater.from(this).inflate(R.layout.subtitle_full, null);
        fullScroll = fullOverlay.findViewById(R.id.full_scroll);
        fullList = fullOverlay.findViewById(R.id.full_list);
        fullTitle = fullOverlay.findViewById(R.id.full_title);
        fullHint = fullOverlay.findViewById(R.id.full_hint);
        fullTrans = fullOverlay.findViewById(R.id.full_trans);
        fullOverlay.findViewById(R.id.full_close).setOnClickListener(v -> closeFullSubtitle());
        fullTrans.setOnClickListener(v -> toggleTranslation());
        refreshFullTransButton();
        // 用户接触/滑动列表 → 立即暂停自动跟随（自由浏览），并停掉正在进行的自动滚动动画；
        // 否则句子切换瞬间发起的 smoothScrollTo 动画会把刚滑走的位置拉回去
        fullScroll.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN || act == android.view.MotionEvent.ACTION_MOVE) {
                if (fullFollow) {
                    fullFollow = false;
                    updateFullHint();
                    fullScroll.fling(0);   // 停止 Scroller 动画（速度置 0）
                    fullScroll.scrollTo(fullScroll.getScrollX(), fullScroll.getScrollY());
                }
            }
            return false;   // 不消费事件，滚动照常
        });
        fullHint.setOnClickListener(v -> {
            fullFollow = true;
            updateFullSubtitle(player != null ? player.getCurrentPosition() : 0, true);   // 立即滚回当前句
            updateFullHint();
        });
        addContentView(fullOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fullOverlay.setVisibility(View.GONE);
    }

    private void updateFullHint() {
        if (fullHint == null) return;
        fullHint.setText(fullFollow
                ? "♪ 跟随播放中 · 上下滑动自由浏览 · 点击句子从此处播放"
                : "已暂停跟随（自由浏览）· 点击句子从此处播放，或点此恢复跟随");
    }

    private void openFullSubtitle() {
        if (subKey == null) {
            Toast.makeText(this, "请先开启字幕并播放歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        ensureFullOverlay();
        fullTitle.setText(currentSong != null && currentSong.title != null ? currentSong.title : "歌词字幕");
        fullFollow = true;   // 打开时默认跟随当前句
        rebuildFullList();
        fullOverlay.setVisibility(View.VISIBLE);
        updateFullHint();
        // 打开即定位当前句：不能只靠播放 ticker（暂停时 ticker 不跑，会一直停在列表顶部，
        // 且用户拖超回退/暂停等待后全屏页不更新——"滚动字幕不滚了"的根因之一）。
        // 必须等列表 measure/layout 完成再算滚动目标：addView 后布局是异步的，
        // post/postDelayed 都可能抢在 traversal 之前执行，此时 row.getTop()=0，
        // smoothScrollTo 目标被算成 0，列表停在顶部（真机日志验证：active 正确但 scrollY 恒为 0）。
        // OnGlobalLayoutListener 保证布局完成后回调，位置精确、无闪烁。
        // 注意：此处不要立即调 updateFullSubtitle——布局前调用会把 lastFullActive 设成当前句，
        // 布局后回调反而因 active 未变被跳过，永远停在顶部。
        locateFullSubtitleAfterLayout();
    }

    private void closeFullSubtitle() {
        if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
    }

    /** 构建全屏列表一行（时间段 + 原文 + 译文；tag=分片号，供增量刷新定位）。 */
    private View buildFullRow(SubtitleManager.SubtitleLine l, int segIdx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(8));
        row.setTag(new FullRowTag(l, segIdx));

        TextView tvTime = new TextView(this);
        tvTime.setText(fmt(l.startMs) + " - " + fmt(l.endMs));
        tvTime.setTextSize(11);
        tvTime.setTextColor(0xFF5A5670);
        row.addView(tvTime);

        TextView tvTxt = new TextView(this);
        tvTxt.setText(l.noSpeech ? "（未检测到人声）" : (l.text.isEmpty() ? "…" : l.text));
        tvTxt.setTextSize(17);
        tvTxt.setTextColor(0xFFC9C6DD);
        tvTxt.setLineSpacing(dp(2), 1.0f);
        row.addView(tvTxt);

        if (!l.translated.isEmpty()) {
            TextView tvTr = new TextView(this);
            tvTr.setText(l.translated);
            tvTr.setTextSize(14);
            tvTr.setTextColor(0xFF8A86A3);
            tvTr.setLineSpacing(dp(2), 1.0f);
            row.addView(tvTr);
        }
        // 点击某一行 → 从该句开始播放（同时恢复跟随）
        final SubtitleManager.SubtitleLine line = l;
        row.setClickable(true);
        row.setOnClickListener(v -> onFullLineClick(line));
        return row;
    }

    /** 全屏页增量刷新：字幕生成中每完成一个分片调用。
     *  只对该分片的行做「追加（新分片）/ 更新文本（补翻译）」，绝不全量重建列表——
     *  长音频几百行字幕每分片全量 removeAllViews+addView 会触发 GC 风暴、主线程卡死（Slow main thread）。 */
    private void refreshFullList(int segIdx) {
        if (fullList == null) return;
        long segStart = subMgr.segmentStartMs(segIdx);
        long segEnd = subMgr.segmentEndMs(segIdx, subDuration);
        List<SubtitleManager.SubtitleLine> segLines = new ArrayList<>();
        for (SubtitleManager.SubtitleLine l : subLines) {
            if (l.startMs >= segStart && l.startMs < segEnd) segLines.add(l);
            else if (!segLines.isEmpty()) break;   // 已越过该分片区间
        }
        if (segLines.isEmpty()) {
            // 纯静音分片完成后，原来的“未生成”色块可能需要缩短或消失。
            rebuildFullList();
            return;
        }
        // 全屏列表中该分片已有的行（tag.segment==segIdx；0 个=新分片，N 个=补翻译刷新）
        List<LinearLayout> rows = new ArrayList<>();
        for (int i = 0; i < fullList.getChildCount(); i++) {
            Object tag = fullList.getChildAt(i).getTag();
            if (tag instanceof FullRowTag && ((FullRowTag) tag).segment == segIdx) rows.add((LinearLayout) fullList.getChildAt(i));
        }
        if (rows.isEmpty()) {
            // 新分片可能跨过了尚未生成的区间；重建才能插入固定高度的分隔色块。
            rebuildFullList();
        } else {
            // 补翻译（译文更新）：行结构/顺序不变，只更新文本，避免整行重建
            int n = Math.min(rows.size(), segLines.size());
            for (int i = 0; i < n; i++) {
                LinearLayout row = rows.get(i);
                SubtitleManager.SubtitleLine l = segLines.get(i);
                updateFullRow(row, l, segIdx);
            }
            // 行数不一致（罕见：识别重跑该分片）：多删少补
            for (int i = rows.size() - 1; i >= n; i--) fullList.removeView(rows.get(i));
            if (segLines.size() > n) {
                int at = fullList.indexOfChild(rows.get(rows.size() - 1)) + 1;
                for (int i = n; i < segLines.size(); i++) fullList.addView(buildFullRow(segLines.get(i), segIdx), at++);
            }
        }
        // 末尾行数变化 → 高亮索引可能失效，复位后由下次 tick 重新定位
        if (lastFullActive >= fullList.getChildCount()) lastFullActive = -1;
    }

    /** 增量更新一行，补翻译后动态添加/移除译文 TextView，避免译文生成了但全屏页不显示。 */
    private void updateFullRow(LinearLayout row, SubtitleManager.SubtitleLine l, int segIdx) {
        row.setTag(new FullRowTag(l, segIdx));
        if (row.getChildCount() > 1) {
            ((TextView) row.getChildAt(1))
                    .setText(l.noSpeech ? "（未检测到人声）" : (l.text.isEmpty() ? "…" : l.text));
        }
        if (!l.translated.isEmpty()) {
            if (row.getChildCount() > 2) {
                ((TextView) row.getChildAt(2)).setText(l.translated);
            } else {
                TextView tvTr = new TextView(this);
                tvTr.setText(l.translated); tvTr.setTextSize(14); tvTr.setTextColor(0xFF8A86A3);
                tvTr.setLineSpacing(dp(2), 1.0f); row.addView(tvTr);
            }
        } else if (row.getChildCount() > 2) {
            row.removeViewAt(2);
        }
    }

    /** 重建全屏列表：每行 = 时间段 + 原文 + 译文；当前句由 updateFullSubtitle 高亮居中。
     *  自由浏览（手动滑动）时重建会保留滚动位置，不打断用户阅读 */
    private void rebuildFullList() {
        if (fullList == null) return;
        final int saveY = fullScroll != null ? fullScroll.getScrollY() : 0;
        fullList.removeAllViews();
        lastFullActive = -1;   // 列表重建：旧行索引全部失效，强制下次 tick 重新高亮
        int previousSegment = -1;
        for (int i = 0; i < subLines.size(); i++) {
            SubtitleManager.SubtitleLine l = subLines.get(i);
            int segIdx = subMgr.getSegmentMs() > 0 ? (int) (l.startMs / subMgr.getSegmentMs()) : -1;
            if (segIdx >= 0 && segIdx > previousSegment + 1) addMissingGapBlocks(previousSegment, segIdx, subDuration);
            fullList.addView(buildFullRow(l, segIdx));
            previousSegment = Math.max(previousSegment, segIdx);
        }
        // 上下各留半屏空白，让当前句可以滚到屏幕中央
        int half = getResources().getDisplayMetrics().heightPixels / 2;
        fullList.setPadding(dp(16), half, dp(16), half);
        // 自由浏览时：重建后恢复原滚动位置。直接用 scrollTo（ScrollView 会自动 clamp 越界），
        // 不要先算 max 再 clamp——列表重建后下一帧才布局，此时 getHeight() 可能仍是旧值/0，
        // 把目标 clamp 成 0 会让字幕"突然回到开头"（用户已复现抽搐）。
        if (saveY > 0) {
            final int targetY = saveY;
            fullList.post(() -> {
                if (fullScroll != null) fullScroll.scrollTo(0, targetY);
            });
        }
        // 跟随模式：布局完成后重新定位当前句（重建后行索引全失效，lastFullActive=-1，
        // 若 ticker 抢在布局前执行 updateFullSubtitle，getTop()=0 会把滚动目标算成 0、
        // 字幕"突然回到开头"——必须等布局完成再定位）。
        // 自由浏览（fullFollow=false）时不定位，只保留上面 saveY 的阅读位置。
        if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE && fullFollow) {
            locateFullSubtitleAfterLayout();
        }
    }

    /** 在可见字幕之间标出未生成分片；色块高度固定，不按缺失时长拉伸，避免破坏阅读节奏。 */
    private void addMissingGapBlocks(int previousSegment, int nextSegment, long durationMs) {
        int runStart = -1;
        for (int seg = Math.max(0, previousSegment + 1); seg < nextSegment; seg++) {
            if (!processedSegs.contains(seg)) {
                if (runStart < 0) runStart = seg;
            } else if (runStart >= 0) {
                addMissingGap(runStart, seg, durationMs);
                runStart = -1;
            }
        }
        if (runStart >= 0) addMissingGap(runStart, nextSegment, durationMs);
    }

    private void addMissingGap(int firstMissing, int endExclusive, long durationMs) {
        long start = subMgr.segmentStartMs(firstMissing);
        long end = Math.min(durationMs, subMgr.segmentStartMs(endExclusive));
        if (end <= start) return;
        TextView gap = new TextView(this);
        gap.setText(fmt(start) + " - " + fmt(end) + "\n字幕尚未生成");
        gap.setGravity(android.view.Gravity.CENTER);
        gap.setTextColor(0xFFE0D6FF);
        gap.setTextSize(13);
        gap.setLineSpacing(dp(2), 1.0f);
        gap.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55382E4F);
        bg.setCornerRadius(dp(12));
        gap.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(68));
        lp.setMargins(0, dp(8), 0, dp(8));
        fullList.addView(gap, lp);
    }

    /** 等全屏列表 measure/layout 完成后再定位当前句（布局前 row.getTop()=0，滚动会算成 0 停顶部） */
    private void locateFullSubtitleAfterLayout() {
        if (fullList == null) return;
        fullList.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        fullList.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE && fullFollow) {
                            updateFullSubtitle(player != null ? player.getCurrentPosition() : 0);
                        }
                    }
                });
    }

    /** 点击字幕行：从该句开始播放（考虑字幕偏移校准），并恢复跟随模式 */
    private void onFullLineClick(SubtitleManager.SubtitleLine l) {
        if (player == null || l == null) return;
        if (l.noSpeech) {   // 无语音段不可定位到"说话"位置，回到该段时间点即可
            player.seekTo((int) Math.max(0, l.startMs));
        } else {
            // 字幕行在音频位置 (startMs - 偏移) 处开始显示，seek 到声音实际位置
            player.seekTo((int) Math.max(0, l.startMs - subOffsetMs));
        }
        if (!player.isPlaying()) {
            player.start();
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        }
        fullFollow = true;
        updateFullSubtitle(player.getCurrentPosition(), true);   // 点击句子恢复跟随：立即滚到该句中央
        updateFullHint();
    }

    /** 按播放位置更新全屏页：当前句高亮（紫/加粗/略放大），并平滑滚动保持居中。
     *  高亮始终跟随当前句切换（自由浏览时也正常切换，只是不自动滚动、不打扰阅读）。
     *  性能关键：只在当前句切换时增量刷新（旧行恢复 + 新行高亮），绝不每 tick 全量遍历
     *  所有行——长音频几百行字幕全量 setTextSize/setTextColor 每 500ms 跑一遍会直接卡死/ANR。 */
    private void updateFullSubtitle(int posMs) { updateFullSubtitle(posMs, false); }

    /** forceScroll=true：恢复跟随（点击句子/提示条）时调用，即使高亮句没变也立即滚动回屏幕中央，
     *  否则要等下一句开始才滚动（用户感知"恢复跟随不跟手"） */
    private void updateFullSubtitle(int posMs, boolean forceScroll) {
        if (fullOverlay == null || fullOverlay.getVisibility() != View.VISIBLE) return;
        if (subLines.isEmpty()) return;
        // 列表尚未完成首次布局（重建后 addView 的 measure/layout 是异步的）：
        // 此时 row.getTop()/getHeight() 都是 0，滚动目标会算成 0、列表被拉到开头（"字幕抽搐回开头"）。
        // 直接返回，由 rebuildFullList/openFullSubtitle 里的 locateFullSubtitleAfterLayout 在布局后定位。
        if (fullList == null || fullList.getHeight() == 0) return;
        int matchPos = posMs - subOffsetMs;   // 与主界面字幕区同一偏移校准
        int active = -1;
        for (int i = 0; i < subLines.size(); i++) {
            SubtitleManager.SubtitleLine l = subLines.get(i);
            if (matchPos >= l.startMs && matchPos < l.endMs) { active = i; break; }
            // 句子间隙：保持"最近已开始"的句子高亮，直到下一句出现（不闪空、不限制时长）
            if (l.startMs <= matchPos) active = i;
        }
        // 高亮没变且非强制：不动任何 view、不重启滚动动画
        if (active == lastFullActive && !forceScroll) return;
        if (active != lastFullActive) {
            highlightFullRow(fullChildIndexForLine(lastFullActive), false);
            highlightFullRow(fullChildIndexForLine(active), true);
            lastFullActive = active;
        }
        // 跟随模式：当前句切换时平滑滚到中间（同句内位置固定，无需滚动）。
        // active 变化即滚动（含间隙保持句）：自然播放中间隙时 active 不变（保持上一句），
        // 不会误滚；但 seek/强制回退落到间隙时 active 会跳变，此时必须滚动到新位置，
        // 否则列表停在顶部（"光标回退后滚动字幕不滚、高光不对"）。
        // forceScroll（恢复跟随）时即使 active 没变也执行滚动：立即把当前句放回屏幕中央。
        if (fullFollow && active >= 0) {
            View row = fullList.getChildAt(fullChildIndexForLine(active));
            if (row != null) {
                int target = row.getTop() - fullScroll.getHeight() / 2 + row.getHeight() / 2;
                int max = Math.max(0, fullList.getHeight() - fullScroll.getHeight());
                target = Math.max(0, Math.min(target, max));
                fullScroll.smoothScrollTo(0, target);
            }
        }
    }

    /** 分隔色块会占用列表子项，不能再用字幕行索引直接 getChildAt。 */
    private int fullChildIndexForLine(int lineIndex) {
        if (fullList == null || lineIndex < 0 || lineIndex >= subLines.size()) return -1;
        SubtitleManager.SubtitleLine target = subLines.get(lineIndex);
        for (int i = 0; i < fullList.getChildCount(); i++) {
            Object tag = fullList.getChildAt(i).getTag();
            if (tag instanceof FullRowTag && ((FullRowTag) tag).line == target) return i;
        }
        return -1;
    }

    /** 只刷新指定一行的当前句高亮（cur=true 高亮 / false 恢复普通样式） */
    private void highlightFullRow(int i, boolean cur) {
        if (i < 0 || i >= fullList.getChildCount()) return;
        LinearLayout row = (LinearLayout) fullList.getChildAt(i);
        if (row.getChildCount() > 1) {
            TextView tv = (TextView) row.getChildAt(1);
            tv.setTextColor(cur ? 0xFFB79CFF : 0xFFC9C6DD);
            tv.getPaint().setFakeBoldText(cur);
            tv.setTextSize(cur ? 19 : 17);
        }
        if (row.getChildCount() > 2) {
            TextView tv = (TextView) row.getChildAt(2);
            tv.setTextColor(cur ? 0xFFE0D6FF : 0xFF8A86A3);
        }
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    /** 管线回调（主线程） */
    private final SubtitlePipeline.Listener pipelineListener = new SubtitlePipeline.Listener() {
        @Override public void onSegmentStarted(int segIdx) {
            subCurrentSeg = segIdx;
            subProgress = 5;
            txtSubStatus.setText("正在生成第 " + (segIdx + 1) + "/" + subTotalSegs + " 段字幕…");
        }

        @Override public void onProgress(int segIdx, int percent) {
            subCurrentSeg = segIdx;
            subProgress = percent;
            progressView.setData(subDuration, subTotalSegs, processedSegs, noSpeechSegs, cloudSegs, segIdx, percent);
        }

        @Override public void onSegmentDone(int segIdx, boolean noSpeech) {
            processedSegs.add(segIdx);
            if (noSpeech) noSpeechSegs.add(segIdx);
            // 记录该分片是否为联网 ASR 生成（进度条蓝色标记）；补翻译触发的第二次回调会重读缓存保持一致
            if (subMgr.isCloudSegment(subKey, segIdx)) cloudSegs.add(segIdx);
            // 完成复位：进度条该分片不再以"处理中亮色"覆盖，按来源显示紫(离线)/蓝(联网)
            subCurrentSeg = -1;
            subProgress = 0;
            // 同一分片可能被触发两次：识别完成一次 + 补翻译完成一次（译文更新后刷新 UI）。
            // 若直接 addAll 会导致该分片句子在 subLines 里重复（UI 显示"原文、原文、译文"三行）。
            // 先移除该分片旧行（按分片时间范围匹配），再载入最新内容（译文随补翻译更新）。
            removeSegmentLines(segIdx);
            subLines.addAll(subMgr.loadSegment(subKey, segIdx));
            sortSubLines();
            txtSubStatus.setText("已生成 " + processedSegs.size() + "/" + subTotalSegs + " 段字幕…");
            updateSubtitle(player != null ? player.getCurrentPosition() : 0);
            if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE) {
                refreshFullList(segIdx);   // 全屏页开着：增量追加/更新该分片行（不全量重建，防卡死）
            }
            maybeAutoStartPlayback(segIdx);
        }

        /** 从 subLines 移除指定分片的所有行（用于 onSegmentDone 去重刷新，防止补翻译重复触发导致行重复） */
        private void removeSegmentLines(int segIdx) {
            if (subLines == null || subLines.isEmpty()) return;
            long segStart = subMgr.segmentStartMs(segIdx);
            long segEnd = subMgr.segmentEndMs(segIdx, subDuration);
            List<SubtitleManager.SubtitleLine> keep = new ArrayList<>();
            for (SubtitleManager.SubtitleLine l : subLines) {
                if (l.startMs < segStart || l.startMs >= segEnd) keep.add(l);
            }
            subLines.clear();
            subLines.addAll(keep);
        }

        @Override public void onAllDone() {
            txtSubStatus.setText("字幕已全部生成 ✓");
            btnComplete.setVisibility(View.GONE);
            if (subMgr.hasFullCache(subKey)) {
                subLines.clear();
                subLines.addAll(subMgr.loadFullCache(subKey));
            }
            if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE) {
                rebuildFullList();
            }
            progressView.setData(subDuration, subTotalSegs, processedSegs, noSpeechSegs, cloudSegs, -1, 100);
            maybeAutoStartPlayback(waitSeg);
        }

        @Override public void onModelPreparing(String engineLabel) {
            txtSubStatus.setText("正在准备 " + engineLabel + " 识别引擎（首次约需 1-2 分钟）…");
            setEngineBadge(engineLabel);
        }

        @Override public void onInfo(String msg) {
            txtSubStatus.setText(msg);
        }

        @Override public void onModelMissing() {
            showModelDialog();
        }

        @Override public void onEngineError(String msg) {
            if (!activityVisible) {
                pendingEngineError = msg;
                if (playback != null && playback.isPlaying()) playback.pause();
                return;
            }
            showEngineErrorDialog(msg);
        }
    };

    private void showEngineErrorDialog(String msg) {
        if (isFinishing() || isDestroyed()) return;
        if (playback != null && playback.isPlaying()) playback.pause();
        new AlertDialog.Builder(MainActivity.this)
                    .setTitle("联网识别不可用")
                    .setMessage(msg + "\n\n是否开启离线 ASR 并继续？")
                    .setPositiveButton("开启离线 ASR", (d, w) -> {
                        settings.setOfflineAsrEnabled(true);
                        settings.setCloudAsrEnabled(false);
                        if (playback != null && playback.song != null) {
                            playback.configureSubtitles();
                            playback.start();
                        }
                    })
                    .setNegativeButton("停止生成", null)
                    .setOnCancelListener(d -> {})
                    .show();
    }

    /** 常驻引擎标识：联网·厂商（紫底）/ 离线·引擎（灰底），显示期间不被状态文字覆盖 */
    private void setEngineBadge(String label) {
        if (txtEngineBadge == null) return;
        txtEngineBadge.setText(label);
        boolean online = label != null && label.startsWith("联网");
        txtEngineBadge.setBackgroundResource(online ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        txtEngineBadge.setTextColor(online ? 0xFFFFFFFF : 0xFFB79CFF);
        txtEngineBadge.setVisibility(View.VISIBLE);
    }

    /** 首次加载等待逻辑：等待的目标分片字幕就绪后自动开始播放（恢复中段播放时等该分片，
     *  从头播放时=分片 0，行为与原来一致） */
    private void maybeAutoStartPlayback(int doneSeg) {
        // Subtitle completion never overrides a user's explicit pause.
    }

    /** 模型异常缺失提示（正常情况下模型已内置安装包、自动复制即可用；仅在复制/兜底失败时出现） */
    private void showModelDialog() {
        boolean whisper = "whisper".equals(settings.getAsrEngine());
        String modelName = whisper ? "Whisper-small" : "SenseVoice-small-int8";
        String modelDesc = whisper
                ? "模型文件（encoder.onnx / decoder.onnx / tokens.txt）"
                : "模型文件（model.int8.onnx / tokens.txt）";
        String modelDir = whisper ? ModelManager.whisperDir(this).getAbsolutePath()
                                  : ModelManager.sensevoiceDir(this).getAbsolutePath();
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("ASR 模型不可用");
        b.setMessage("字幕识别模型（" + modelName + "）已内置安装包，但自动复制失败。\n\n" +
                "可尝试：重新打开音频（重试复制）；或手动把 " + modelDesc + " 放到：\n" +
                modelDir);
        b.setPositiveButton("重试", (d, w) -> {
            final ProgressDialog pd = ProgressDialog.show(this, "准备模型", "正在复制内置模型…", true, false);
            new Thread(() -> {
                boolean ok = whisper ? ModelManager.ensureWhisperModels(this)
                                     : ModelManager.ensureModels(this);
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (ok) {
                        Toast.makeText(this, "模型就绪，开始生成字幕", Toast.LENGTH_LONG).show();
                        if (currentSong != null && subKey != null) setupSubtitle(currentSong, (int) subDuration);
                    } else {
                        Toast.makeText(this, "仍失败，请手动放置模型文件后重新打开音频", Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        });
        b.setNegativeButton("稍后", null);
        b.show();
    }

    // ---------- 进度刷新 ----------

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null) {
                if (!userSeeking) seekBar.setProgress(player.getCurrentPosition());
                txtCur.setText(fmt(player.getCurrentPosition()));
                txtTotal.setText(fmt(player.getDuration()));
                updateSubtitle(player.getCurrentPosition());
                if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE) {
                    updateFullSubtitle(player.getCurrentPosition());
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private String fmt(long ms) {
        long t = ms / 1000;
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    // ---------- 生命周期（控温 / 防杀进程 / 保存断点） ----------

    /** 返回键：全屏滚动字幕页打开时优先关闭全屏页（回到主界面），而不是直接退出 App。
     *  Android 13+（预测性返回）下 BACK 事件会先派发 dispatchKeyEvent、随后再触发
     *  OnBackInvokedCallback —— 两处都拦截会"先关全屏、再退出 App"。因此这里不拦截
     *  keyevent，统一交给 onStart() 注册的 OnBackInvokedCallback（handleSystemBack）处理。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    /** 预测性返回（Android 13+ 手势/系统返回统一入口） */
    private void handleSystemBack() {
        Log.i("MainActivity", "handleSystemBack fullOverlay=" + (fullOverlay != null ? fullOverlay.getVisibility() : "null"));
        if (fullOverlay != null && fullOverlay.getVisibility() == View.VISIBLE) {
            closeFullSubtitle();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        activityStarted();
        if (pendingEngineError != null) {
            final String pending = pendingEngineError;
            pendingEngineError = null;
            handler.post(() -> showEngineErrorDialog(pending));
        }
        // 预测性返回回调在 onStart 之后才生效（onCreate 里注册会被系统忽略）；
        // 卸载旧回调防止重复注册（onStart 可能多次进入）
        if (Build.VERSION.SDK_INT >= 33) {
            if (backCallbackRegistered) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            backCallbackRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        activityVisible = false;
        activityStopped();
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // PlaybackService owns playback and subtitle processing while backgrounded.
        savePlaybackState();
    }

    /** 记忆当前歌曲与播放位置（重启 App 后恢复） */
    private void savePlaybackState() {
        if (currentSong == null || player == null) return;
        prefs.edit()
                .putString(KEY_LAST_URI, currentSong.uri != null ? currentSong.uri.toString() : null)
                .putString(KEY_LAST_PATH, currentSong.path)
                .putLong(KEY_LAST_POS, player.getCurrentPosition())
                .putBoolean(KEY_LAST_PLAYING, player.isPlaying())
                .apply();
    }

    /** 启动后恢复上次的歌曲与播放位置（仅在非外部「打开方式」进入时） */
    private void restoreLastPlayback() {
        if (playback.song != null) { onPlaybackChanged(); return; }
        String uri = prefs.getString(KEY_LAST_URI, null), path = prefs.getString(KEY_LAST_PATH, null);
        if (uri == null && path == null) return;
        ArrayList<Song> candidates = new ArrayList<>(allSongs); candidates.addAll(fileSongs);
        for (Song s : candidates) if (matchesLast(s, uri, path)) {
            player = playback;
            playback.setQueue(candidates, playMode);
            // Restore position but wait for explicit playback after a process restart.
            playback.play(s, (int)prefs.getLong(KEY_LAST_POS, 0), false);
            return;
        }
    }

    private boolean matchesLast(Song s, String lastUri, String lastPath) {
        if (lastUri != null && s.uri != null && s.uri.toString().equals(lastUri)) return true;
        if (lastPath != null && lastPath.equals(s.path)) return true;
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playback != null) {
            playback.configureSubtitles();
            if (playback.song != null) { onPlaybackChanged(); if (playback.getDuration() > 0) setupSubtitle(playback.song, playback.getDuration()); }
            refreshSubtitleButton(); refreshTransButton();
        }
    }

    private static final String KEY_WATCHDOG_RESTART = "watchdog_restart";

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (playback != null) playback.detach(this);
        if (bulkGen != null) bulkGen.setListener(null);
        super.onDestroy();
    }

    // ---------- 专辑网格适配器（可视化歌单） ----------

    private class AlbumAdapter extends BaseAdapter {
        @Override public int getCount() { return albums.size(); }
        @Override public Object getItem(int p) { return albums.get(p); }
        @Override public long getItemId(int p) { return albums.get(p).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_album, parent, false);
                h = new ViewHolder();
                h.cover = convertView.findViewById(R.id.img_cover);
                h.name = convertView.findViewById(R.id.txt_album);
                h.artist = convertView.findViewById(R.id.txt_artist);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            final Album a = albums.get(position);
            int colW = gridBox.getColumnWidth();
            if (colW > 0) {
                ViewGroup.LayoutParams lp = h.cover.getLayoutParams();
                lp.height = colW;
                h.cover.setLayoutParams(lp);
            }
            h.name.setText(a.name);
            h.artist.setText(a.artist == null || a.artist.isEmpty() ? "未知艺术家" : a.artist);
            h.cover.setImageResource(R.drawable.ic_album_default);
            AlbumArtLoader.load(MainActivity.this, a.id, a.artPath, null, h.cover);
            convertView.setOnClickListener(v -> showAlbumSongs(a));
            return convertView;
        }
    }

    static class ViewHolder {
        ImageView cover;
        TextView name, artist;
    }

    // ---------- 专辑封面异步加载（带内存缓存） ----------

    static class AlbumArtLoader {
        // key 用「专辑id + 音频uri/路径」组合：媒体库歌按专辑区分，文件导入的歌 albumId 全是 0，
        // 若只按 albumId 缓存会互相覆盖（多首文件歌显示同一张封面）
        private static final LruCache<String, Bitmap> cache = new LruCache<>(128);

        static void load(final Activity act, final long albumId, final String artPath,
                         final Uri audioUri, final ImageView iv) {
            final String key = albumId + "|" + (audioUri != null ? audioUri.toString() : artPath);
            iv.setTag(key);
            Bitmap b = cache.get(key);
            if (b != null) { iv.setImageBitmap(b); return; }
            iv.setImageResource(R.drawable.ic_album_default);
            new Thread(() -> {
                Bitmap bm = decode(act, albumId, artPath, audioUri);
                if (bm != null) {
                    cache.put(key, bm);
                    iv.post(() -> { if (key.equals(iv.getTag()) && !act.isDestroyed()) iv.setImageBitmap(bm); });
                }
            }).start();
        }

        private static Bitmap decode(Activity act, long albumId, String artPath, Uri audioUri) {
            Bitmap bm = null;
            // 1) 专辑艺术图路径（旧版有效；Android 13+ 上路径读取常被拒）
            if (artPath != null) bm = decodeFile(artPath);
            // 2) 从音频 content URI 提取内嵌封面（Android 13+ 标准做法，仅需媒体权限）
            if (bm == null && audioUri != null) {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(act, audioUri);
                    byte[] art = mmr.getEmbeddedPicture();
                    if (art != null) bm = BitmapFactory.decodeByteArray(art, 0, art.length);
                } catch (Exception ignored) {
                } finally {
                    try { mmr.release(); } catch (Exception ignored) {}
                }
            }
            // 3) 旧式 albumart content URI 兜底
            if (bm == null) {
                try {
                    Uri u = Uri.parse("content://media/external/audio/albumart/" + albumId);
                    java.io.InputStream in = act.getContentResolver().openInputStream(u);
                    if (in != null) {
                        bm = BitmapFactory.decodeStream(in);
                        in.close();
                    }
                } catch (Exception ignored) {}
            }
            return bm;
        }

        private static Bitmap decodeFile(String path) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int scale = 1;
                while (o.outWidth / scale > 512 || o.outHeight / scale > 512) scale *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = scale;
                return BitmapFactory.decodeFile(path, o2);
            } catch (Exception e) { return null; }
        }
    }

    static class Song {
        final String title, artist, path;
        final Uri uri;
        final long duration, albumId;
        int source;        // 0=媒体库 1=单文件 2=文件夹扫描
        Uri treeUri;       // source=2：所属文件夹的 tree URI（删除用）
        String treeLabel;  // source=2：文件夹显示名
        boolean stale;     // 失效标记
        long lastModified; // 毫秒时间戳（新歌排前），0=未知
        Song(String t, String a, long d, String p, Uri u, long l) {
            title = t; artist = a; duration = d; path = p; uri = u; albumId = l;
        }
    }

    static class Album {
        final long id;
        final String name, artist, artPath;
        Album(long i, String n, String a, String c) { id = i; name = n; artist = a; artPath = c; }
    }
}

