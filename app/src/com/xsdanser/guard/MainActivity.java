package com.xsdanser.guard;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * 纯代码布局（无 XML 布局资源）：状态卡 + 设备/间隔 + 守护任务管理 + 操作按钮 + 倒序日志 + 说明。
 * v3.0：从「只守护学时兑」改为通用守护 —— 可添加任意 App 任务，每个任务独立设置包名、
 * 自动安装 / 授权 / 拉起开关，安装包来源二选一：固定下载网址 或 手机本地选择 APK 推送。
 */
public class MainActivity extends Activity {
    /** 运行日志默认只展示最新 10 行，其余折叠在「展开」按钮后。 */
    private static final int LOG_PREVIEW = 10;
    /** 包名列表默认只展示前 12 个，其余折叠。 */
    private static final int PKG_PREVIEW = 12;
    private static final int REQ_PICK_APK = 3301;

    // ---- 主题配色：同一色系，主色仅用于可点的主操作与状态强调 ----
    private static final int BG = 0xFF0E1720;
    private static final int CARD = 0xFF16232E;
    private static final int CARD_IN = 0xFF1D2E3A;
    private static final int LINE = 0xFF2A3D4A;
    private static final int TEXT = 0xFFE8F1F4;
    private static final int SUB = 0xFF93AAB6;
    private static final int LOG_TEXT = 0xFFB6C8D2;
    private static final int BRAND = 0xFF14B8A6;
    private static final int BRAND_LIGHT = 0xFF2DD4BF;
    private static final int DANGER = 0xFFEF4444;
    private static final int ON_BRAND = 0xFF062A26;
    private static final int DISABLED_TEXT = 0xFF647C88;

    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvMeta;
    private TextView tvLog;
    private TextView tvSourceMode;
    private EditText etDevices;
    private EditText etInterval;
    /** 任务表单的安装包地址输入框：网址来源填 URL，本地来源显示 content:// URI。 */
    private EditText etApkUrl;
    private EditText etTaskLabel;
    private EditText etTaskPkg;
    private LinearLayout taskListBox;
    private LinearLayout pkgListBox;
    private TextView pkgListHint;
    private Button btnPkgMore;
    private Button btnQueryPkgs;
    private boolean showAllPkgs;
    private Button btnLogMore;
    private Button btnSourceToggle;
    private Button btnPickApk;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStart;
    private Button btnInstall;

    private final ArrayList<GuardTask> tasks = new ArrayList<GuardTask>();
    private final HashMap<String, TextView> taskStatusViews = new HashMap<String, TextView>();
    private String editingId = null;
    private int editSource = GuardTask.SRC_URL;
    private boolean showAllLog;
    private boolean wide;

    /** 所有按钮统一在此登记，状态变化时按「主/次 + 可用/禁用」重刷配色。 */
    private final ArrayList<Button> buttons = new ArrayList<Button>();

    private final Runnable refresher = new Runnable() {
        public void run() { refresh(); }
    };

    /** 配置自动保存：输入变化后 400ms 防抖落盘，页面退出时立即落盘。 */
    private final Runnable saveTask = new Runnable() {
        public void run() { savePrefs(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("学时兑守护");
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setBackgroundDrawable(new ColorDrawable(BG));
            ab.setTitle(Html.fromHtml("<font color='#E8F1F4'>学时兑守护</font>"));
        }
        try {
            java.lang.reflect.Method m = getWindow().getClass()
                    .getMethod("setStatusBarColor", int.class);
            m.invoke(getWindow(), Integer.valueOf(BG));
        } catch (Throwable ignored) {}

        Intent launch = getIntent();
        if (launch != null) {
            String override = launch.getStringExtra("device");
            if (override != null && override.trim().length() > 0
                    && override.lastIndexOf(':') > 0) {
                new GuardPrefs(this).setDevicesRaw("临时设备|" + override.trim() + "|1");
            }
        }
        GuardLog.load(this);
        tasks.addAll(new GuardPrefs(this).tasks());

        int sw = getResources().getDisplayMetrics().widthPixels;
        wide = sw > 1200;
        int pad = wide ? (int) (sw * 0.16) : dp(18);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, dp(16), pad, dp(28));
        // ScrollView 内容必须用 WRAP_CONTENT：否则子布局被固定成一屏高，下方任务 / 日志看不到也滚不动
        sv.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 状态卡 ----
        LinearLayout statusCard = card();
        tvStatus = new TextView(this);
        tvStatus.setTextSize(wide ? 20 : 17);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setTextColor(TEXT);
        tvStatus.setLineSpacing(0, 1.2f);
        statusCard.addView(tvStatus);

        tvDetail = new TextView(this);
        tvDetail.setTextSize(wide ? 14 : 12);
        tvDetail.setTextColor(SUB);
        tvDetail.setPadding(0, dp(6), 0, dp(2));
        statusCard.addView(tvDetail);

        tvMeta = new TextView(this);
        tvMeta.setTextSize(wide ? 13 : 11);
        tvMeta.setTextColor(SUB);
        tvMeta.setPadding(0, dp(2), 0, 0);
        tvMeta.setLineSpacing(0, 1.3f);
        statusCard.addView(tvMeta);
        root.addView(statusCard, cardLp());

        // ---- 连接配置卡 ----
        LinearLayout cfg = card();
        cfg.addView(label("设备列表（名称|IP:端口[,备用地址]|1开0关；多台用分号隔开）"));
        etDevices = new EditText(this);
        etDevices.setMaxLines(1);
        etDevices.setLines(1);
        etDevices.setHorizontallyScrolling(true);
        etDevices.setText(new GuardPrefs(this).devicesRaw()
                .replace('\r', ';').replace('\n', ';').replace('；', ';'));
        styleEdit(etDevices);
        cfg.addView(etDevices, editLp());

        cfg.addView(label("检测间隔（秒，3~600）"));
        etInterval = new EditText(this);
        etInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        etInterval.setText(String.valueOf(new GuardPrefs(this).intervalSec()));
        styleEdit(etInterval);
        cfg.addView(etInterval, editLp());
        root.addView(cfg, cardLp());

        // ---- v3：守护任务表单 ----
        LinearLayout form = card();
        TextView formTitle = label("守护任务（可添加任意 App；默认已预置「学时兑」）");
        formTitle.setTextColor(BRAND_LIGHT);
        formTitle.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(formTitle);

        form.addView(label("任务名称"));
        etTaskLabel = new EditText(this);
        etTaskLabel.setSingleLine(true);
        styleEdit(etTaskLabel);
        form.addView(etTaskLabel, editLp());

        form.addView(label("目标 App 包名（例 com.xsdanser.tv）"));
        etTaskPkg = new EditText(this);
        etTaskPkg.setSingleLine(true);
        styleEdit(etTaskPkg);
        form.addView(etTaskPkg, editLp());

        tvSourceMode = label("安装包来源：固定下载网址（点右侧按钮切换为本地 APK）");
        tvSourceMode.setTextColor(BRAND_LIGHT);
        form.addView(tvSourceMode);

        etApkUrl = new EditText(this);
        etApkUrl.setMaxLines(1);
        etApkUrl.setLines(1);
        etApkUrl.setHorizontallyScrolling(true);
        etApkUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etApkUrl.setText(new GuardPrefs(this).apkUrl());
        styleEdit(etApkUrl);
        form.addView(etApkUrl, editLp());

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        btnSourceToggle = miniButton("来源：网址", new View.OnClickListener() {
            public void onClick(View v) {
                editSource = (editSource == GuardTask.SRC_URL)
                        ? GuardTask.SRC_LOCAL : GuardTask.SRC_URL;
                updateSourceUi();
            }
        }, false);
        btnPickApk = miniButton("选择本地 APK", new View.OnClickListener() {
            public void onClick(View v) { pickLocalApk(); }
        }, false);
        sourceRow.addView(btnSourceToggle, lp());
        sourceRow.addView(btnPickApk, lpLast());
        form.addView(sourceRow);

        LinearLayout formRow = new LinearLayout(this);
        formRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSave = miniButton("保存任务", new View.OnClickListener() {
            public void onClick(View v) { saveTaskConfig(); }
        }, true);
        Button btnNew = miniButton("新增空白", new View.OnClickListener() {
            public void onClick(View v) { clearTaskForm(); }
        }, false);
        formRow.addView(btnSave, lp());
        formRow.addView(btnNew, lpLast());
        form.addView(formRow);
        root.addView(form, cardLp());

        // ---- 任务列表 ----
        LinearLayout taskCard = card();
        TextView taskTitle = label("已配置任务");
        taskTitle.setTextColor(BRAND_LIGHT);
        taskTitle.setTypeface(Typeface.DEFAULT_BOLD);
        taskCard.addView(taskTitle);
        taskListBox = new LinearLayout(this);
        taskListBox.setOrientation(LinearLayout.VERTICAL);
        taskCard.addView(taskListBox);
        root.addView(taskCard, cardLp());
        rebuildTaskList();

        // ---- v3.1：查询电视第三方应用包名 ----
        LinearLayout pkgCard = card();
        TextView pkgTitle = label("电视第三方应用包名（复制后可填到上方任务包名）");
        pkgTitle.setTextColor(BRAND_LIGHT);
        pkgTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pkgCard.addView(pkgTitle);
        btnQueryPkgs = button("查询电视应用包名", new View.OnClickListener() {
            public void onClick(View v) {
                if (!GuardState.adbOk) {
                    GuardLog.i(MainActivity.this, "请先连接 ADB 再查询电视应用包名");
                    return;
                }
                savePrefs();
                GuardLog.i(MainActivity.this, "已请求查询电视应用包名，请查看下方列表与日志");
                GuardService.start(MainActivity.this, GuardService.ACTION_LIST_PKGS);
            }
        });
        pkgCard.addView(btnQueryPkgs, cardLp());
        pkgListHint = label("尚未查询：连接 ADB 后点上方按钮，列出电视里所有第三方应用包名");
        pkgCard.addView(pkgListHint);
        pkgListBox = new LinearLayout(this);
        pkgListBox.setOrientation(LinearLayout.VERTICAL);
        pkgCard.addView(pkgListBox);
        btnPkgMore = button("展开全部包名", new View.OnClickListener() {
            public void onClick(View v) {
                showAllPkgs = !showAllPkgs;
                rebuildPkgList();
            }
        });
        root.addView(pkgCard, cardLp());

        // 三个输入框内容一变就自动保存，不必再点「启动守护」才生效
        etDevices.addTextChangedListener(watcher(etDevices));
        etInterval.addTextChangedListener(watcher(etInterval));
        etApkUrl.addTextChangedListener(watcher(etApkUrl));

        TextView op = label("操作顺序：① 连接 ADB → ② 安装任务 App（已装可跳过）→ ③ 启动守护");
        op.setTextColor(BRAND_LIGHT);
        op.setTypeface(Typeface.DEFAULT_BOLD);
        op.setPadding(0, dp(2), 0, dp(8));
        root.addView(op);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnConnect = button("连接 ADB", new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                GuardService.start(MainActivity.this, GuardService.ACTION_CONNECT);
                GuardLog.i(MainActivity.this, "已请求连接 ADB，请查看下方日志");
            }
        });
        primary(btnConnect);
        row1.addView(btnConnect, lp());
        btnDisconnect = button("断开 ADB", new View.OnClickListener() {
            public void onClick(View v) {
                GuardService.start(MainActivity.this, GuardService.ACTION_DISCONNECT);
                GuardLog.i(MainActivity.this, "已请求断开 ADB，请查看下方日志");
            }
        });
        row1.addView(btnDisconnect, lpLast());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        btnInstall = button("安装任务到电视", new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                GuardService.start(MainActivity.this, GuardService.ACTION_INSTALL);
                GuardLog.i(MainActivity.this, "已请求安装全部启用任务，请查看下方日志");
            }
        });
        primary(btnInstall);
        row2.addView(btnInstall, lpLast());
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = button("启动守护", new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                GuardService.start(MainActivity.this, GuardService.ACTION_START);
                GuardLog.i(MainActivity.this, "已请求启动守护");
            }
        });
        primary(btnStart);
        row3.addView(btnStart, lp());
        row3.addView(button("停止守护", new View.OnClickListener() {
            public void onClick(View v) {
                GuardService.start(MainActivity.this, GuardService.ACTION_STOP);
            }
        }), lpLast());
        root.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(button("清空日志", new View.OnClickListener() {
            public void onClick(View v) {
                GuardLog.clear(MainActivity.this);
                refresh();
            }
        }), lp());
        row4.addView(button("重置 ADB 密钥", new View.OnClickListener() {
            public void onClick(View v) {
                GuardService.resetKey(MainActivity.this);
                GuardLog.i(MainActivity.this, "已重置密钥，下次连接会重新请求授权");
            }
        }), lpLast());
        root.addView(row4);

        int hintSize = wide ? 16 : 13;
        TextView logTitle = label("运行日志（最新在最上）");
        logTitle.setTextSize(hintSize - (wide ? 2 : 1));
        logTitle.setTextColor(BRAND_LIGHT);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(6), 0, dp(6));
        root.addView(logTitle);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(hintSize - (wide ? 4 : 3));
        tvLog.setTextColor(LOG_TEXT);
        tvLog.setBackground(round(CARD, 0, 0, 12));
        tvLog.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvLog.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logLp.setMargins(0, 0, 0, dp(8));
        tvLog.setLayoutParams(logLp);
        root.addView(tvLog);
        btnLogMore = button("展开全部日志", new View.OnClickListener() {
            public void onClick(View v) {
                showAllLog = !showAllLog;
                refresh();
            }
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moreLp.setMargins(0, 0, 0, dp(14));
        btnLogMore.setLayoutParams(moreLp);
        root.addView(btnLogMore);

        TextView hint = new TextView(this);
        hint.setTextSize(hintSize);
        hint.setTextColor(SUB);
        hint.setBackground(round(CARD, 0, 0, 12));
        hint.setPadding(dp(14), dp(12), dp(14), dp(12));
        hint.setLineSpacing(0, 1.3f);
        hint.setText(
            "本 App 除了为「学时兑」App 提供守护，也可以为其他类似的 TV / 盒子 App 提供守护服务：被删自动补装、权限自动补齐、进程被杀自动拉起，做好保活。\n"
          + "\n"
          + "【使用步骤】① 添加守护任务（包名 + 网址或本地 APK）→ ② 连接 ADB → ③ 安装任务 App（已装可跳过）→ ④ 启动守护。\n"
          + "【安装包来源】二选一：固定下载网址（联网下载后推送），或「选择本地 APK」（从手机选包后推送，授权长期有效）；每个任务单独设置。\n"
          + "【三个开关】未安装自动补装 / 自动补齐权限 / 进程掉了自动拉起；不勾就只检测、不动手。安装与授权统一走「安装任务到电视」，启动守护时也会自动补齐权限。\n"
          + "【查包名】连上 ADB 后点「查询电视应用包名」（未连接时为灰色不可点），列出电视里所有第三方应用，点包名即可复制，再粘贴到任务包名输入框。\n"
          + "【连接授权】首次连接若电视弹出「允许 USB 调试吗」，请勾选「始终允许」再点允许；连通后每约 10 秒检测一次。点「断开 ADB」会停止守护且不再自动重连，需重新连接。\n"
          + "【其他】默认预置的「学时兑」任务不可删除（可编辑 / 停用）；老版本升级后原有下载网址与设备配置继续生效。多数盒子同一时间只允许一个 ADB 连接，用本 App 时请停掉电脑上的守护脚本。");
        hint.setLayoutParams(cardLp());
        root.addView(hint);

        setContentView(sv);
        updateSourceUi();
        refresh();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_APK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            // v3.2：不再静默返回 —— 取消 / 失败也给出可见反馈，避免「点了没反应」
            GuardLog.i(this, "未选择本地 APK（已取消，或文件选择器未返回文件）");
            android.widget.Toast.makeText(this, "未选择文件（已取消）",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = data.getData();
        // 持久授权：重启手机 / 重新打开 App 后仍能读取这个安装包。
        // 编译用的旧 framework 桩没有该方法，用反射兼容 API19+ 真机。
        try {
            java.lang.reflect.Method take = getContentResolver().getClass()
                    .getMethod("takePersistableUriPermission", Uri.class, int.class);
            take.invoke(getContentResolver(), uri, Integer.valueOf(0x1));
            take.invoke(getContentResolver(), uri, Integer.valueOf(0x2));
        } catch (Throwable ignored) {}
        String name = displayName(uri);
        boolean apk = name != null
                && name.toLowerCase(Locale.getDefault()).endsWith(".apk");
        editSource = GuardTask.SRC_LOCAL;
        etApkUrl.setText(uri.toString());
        updateSourceUi();
        String tip = apk
                ? "已选择本地 APK：" + name
                : "已选中文件：" + (name == null ? uri.toString() : name)
                  + "（文件名不是 .apk 结尾，若安装失败请重选真实安装包）";
        GuardLog.i(this, tip + "，点「保存任务」后生效");
        android.widget.Toast.makeText(this, tip,
                apk ? android.widget.Toast.LENGTH_SHORT : android.widget.Toast.LENGTH_LONG).show();
    }

    @Override protected void onResume() {
        super.onResume();
        Runnable ui = new Runnable() {
            public void run() { runOnUiThread(refresher); }
        };
        GuardLog.setListener(ui);
        GuardState.onChange = ui;
        refresh();
    }

    @Override protected void onPause() {
        savePrefs();
        GuardLog.setListener(null);
        GuardState.onChange = null;
        super.onPause();
    }

    // ------------------------------------------------------------- 任务管理
    private void updateSourceUi() {
        if (tvSourceMode == null || btnSourceToggle == null || etApkUrl == null) return;
        boolean local = editSource == GuardTask.SRC_LOCAL;
        tvSourceMode.setText("安装包来源：" + (local
                ? "手机本地 APK（点「选择本地 APK」后自动填入，长期有效）"
                : "固定下载网址（App 联网下载后经 ADB 推送安装）"));
        btnSourceToggle.setText(local ? "来源：本地 APK" : "来源：网址");
        etApkUrl.setHint(local ? "content:// ... 或 file:// ..."
                               : "https://example.com/app.apk");
    }

    private void pickLocalApk() {
        try {
            Intent pick = new Intent("android.intent.action.OPEN_DOCUMENT");
            pick.addCategory("android.intent.category.OPENABLE");
            // v3.2：不要用 application/vnd.android.package-archive 过滤。
            // 多数国产 ROM / 第三方文件管理器不把 .apk 识别成这个 MIME，
            // 结果就是列表里 APK 发灰、点了没反应。放开成 */* 保证任何文件都能点选，
            // 选中后再按文件名校验并提示即可。
            pick.setType("*/*");
            // FLAG_GRANT_READ_URI_PERMISSION(1) + FLAG_GRANT_PERSISTABLE_URI_PERMISSION(32)
            pick.addFlags(0x1 | 0x20);
            startActivityForResult(pick, REQ_PICK_APK);
            GuardLog.i(this, "已打开系统文件选择器，请点选一个 .apk 文件");
        } catch (Throwable t) {
            GuardLog.i(this, "无法打开系统文件选择器：" + t);
            android.widget.Toast.makeText(this, "无法打开文件选择器：" + t,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 从 content:// URI 读取用户可见的文件名，用于判断是否 APK。 */
    private String displayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(
                    uri, new String[]{"_display_name"}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int i = c.getColumnIndex("_display_name");
                        if (i >= 0) return c.getString(i);
                    }
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        return uri.getLastPathSegment();
    }

    private void saveTaskConfig() {
        String label = etTaskLabel.getText().toString().trim();
        String pkg = etTaskPkg.getText().toString().trim();
        String value = etApkUrl.getText().toString().trim();
        if (!GuardTask.validPkg(pkg)) {
            GuardLog.i(this, "包名格式不对，应形如 com.example.app");
            return;
        }
        if (value.length() == 0) {
            GuardLog.i(this, editSource == GuardTask.SRC_LOCAL
                    ? "请先点「选择本地 APK」选中一个安装包" : "请填写固定下载网址");
            return;
        }
        if (editSource == GuardTask.SRC_URL
                && !value.startsWith("http://") && !value.startsWith("https://")) {
            GuardLog.i(this, "固定网址应以 http:// 或 https:// 开头");
            return;
        }
        if (editSource == GuardTask.SRC_LOCAL
                && !value.startsWith("content://") && !value.startsWith("file://")) {
            GuardLog.i(this, "本地来源应是文件选择器返回的 content:// 地址，请重新选择");
            return;
        }
        if (label.length() == 0) label = pkg;
        GuardTask t = new GuardTask();
        t.id = (editingId == null || editingId.length() == 0)
                ? ("t" + System.currentTimeMillis()) : editingId;
        t.label = label;
        t.pkg = pkg;
        t.source = editSource;
        t.apkName = nameFromUri(value);
        t.flags = GuardTask.F_INSTALL | GuardTask.F_GRANT | GuardTask.F_LAUNCH;
        t.enabled = true;
        if (editSource == GuardTask.SRC_LOCAL) t.localUri = value; else t.apkUrl = value;

        boolean replaced = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (t.id.equals(tasks.get(i).id)) { tasks.set(i, t); replaced = true; break; }
        }
        if (!replaced) tasks.add(t);
        new GuardPrefs(this).setTasks(tasks);
        GuardState.clearTaskStates(tasks);
        rebuildTaskList();
        clearTaskForm();
        GuardLog.i(this, (replaced ? "已更新任务：" : "已添加任务：") + t.label
                + "（" + t.pkg + " · " + t.sourceName() + "）");
    }

    private static String nameFromUri(String value) {
        try {
            String s = Uri.parse(value).getLastPathSegment();
            return s == null ? "app.apk" : s;
        } catch (Throwable t) { return "app.apk"; }
    }

    private void fillTaskForm(GuardTask t) {
        editingId = t.id;
        etTaskLabel.setText(t.label);
        etTaskPkg.setText(t.pkg);
        editSource = t.source;
        etApkUrl.setText(t.sourceValue());
        updateSourceUi();
    }

    private void clearTaskForm() {
        editingId = null;
        editSource = GuardTask.SRC_URL;
        etTaskLabel.setText("");
        etTaskPkg.setText("");
        etApkUrl.setText("");
        updateSourceUi();
    }

    private void rebuildTaskList() {
        if (taskListBox == null) return;
        taskListBox.removeAllViews();
        taskStatusViews.clear();
        if (tasks.isEmpty()) {
            TextView empty = label("暂无任务：请在上方填写包名并选择安装包来源后保存");
            taskListBox.addView(empty);
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            final GuardTask t = tasks.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(round(CARD_IN, 0, 0, 12));
            box.setPadding(dp(14), dp(11), dp(14), dp(10));

            // 第一行：状态圆点 + 名称（左）+ 来源小标签（右）
            LinearLayout headRow = new LinearLayout(this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView dot = new TextView(this);
            dot.setText(t.enabled ? "●" : "○");
            dot.setTextSize(wide ? 13 : 11);
            dot.setTextColor(t.enabled ? BRAND_LIGHT : DISABLED_TEXT);
            dot.setPadding(0, 0, dp(6), 0);
            headRow.addView(dot);
            TextView name = new TextView(this);
            name.setText(t.label);
            name.setTextSize(wide ? 15 : 13);
            name.setTextColor(t.enabled ? TEXT : DISABLED_TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView src = new TextView(this);
            src.setText(t.sourceName());
            src.setTextSize(wide ? 11 : 10);
            src.setTextColor(BRAND_LIGHT);
            src.setBackground(round(0xFF12312F, 0, 0, 6));
            src.setPadding(dp(7), dp(2), dp(7), dp(2));
            headRow.addView(src);
            box.addView(headRow);

            // 第二行：包名（次要灰字）
            TextView pkg = new TextView(this);
            pkg.setText(t.pkg);
            pkg.setTextSize(wide ? 12 : 10);
            pkg.setTextColor(SUB);
            pkg.setPadding(dp(18), dp(3), 0, 0);
            box.addView(pkg);

            // 第三行：安装状态
            TextView st = new TextView(this);
            st.setTextSize(wide ? 12 : 10);
            st.setTextColor(SUB);
            st.setPadding(dp(18), dp(5), 0, dp(8));
            box.addView(st);
            taskStatusViews.put(t.id, st);

            // 第四行：操作按钮靠右
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.RIGHT);
            // v3.1：任务级「安装 / 权限」按钮取消 —— 与下方全局「安装任务到电视」功能重复，
            // 且任务级安装需要单独占 ADB 连接、失败只写折叠日志，体验上像「点了没反应」。
            // 安装统一走下方按钮（含自动授权），权限在启动守护时由服务自动补齐。
            // v3.4：三个操作按钮统一用紧凑样式（更小字号 / 内边距 / 固定窄宽）。
            row.addView(taskButton("编辑", new View.OnClickListener() {
                public void onClick(View v) { fillTaskForm(t); }
            }), lpTaskBtn());
            row.addView(taskButton(t.enabled ? "停用" : "启用", new View.OnClickListener() {
                public void onClick(View v) {
                    t.enabled = !t.enabled;
                    new GuardPrefs(MainActivity.this).setTasks(tasks);
                    rebuildTaskList();
                    GuardLog.i(MainActivity.this, t.label + (t.enabled ? " 已启用" : " 已停用"));
                }
            }), lpTaskBtn());
            // v3.3：预置「学时兑」是默认任务，禁止删除；其余任务照常可删。
            // v3.4：按钮文字精简为「默认」，仍为灰色禁用态。
            if (GuardTask.DEFAULT_ID.equals(t.id)) {
                Button btnLocked = taskButton("默认", null);
                btnLocked.setEnabled(false);
                styleButton(btnLocked, false, false);
                row.addView(btnLocked, lpTaskBtn());
            } else {
                row.addView(taskButton("删除", new View.OnClickListener() {
                    public void onClick(View v) {
                        tasks.remove(t);
                        new GuardPrefs(MainActivity.this).setTasks(tasks);
                        GuardState.clearTaskStates(tasks);
                        rebuildTaskList();
                        GuardLog.i(MainActivity.this, "已删除任务：" + t.label);
                    }
                }), lpTaskBtn());
            }
            box.addView(row);
            taskListBox.addView(box, editLp());
        }
        refreshTaskStatuses();
    }

    private void refreshTaskStatuses() {
        for (int i = 0; i < tasks.size(); i++) {
            GuardTask t = tasks.get(i);
            TextView v = taskStatusViews.get(t.id);
            if (v == null) continue;
            int state = GuardState.taskInstalled(t.id);
            String s = state == 1 ? "电视已安装" : state == 2 ? "电视未安装" : "安装状态未检测";
            v.setText(s + " · 补装/授权/拉起："
                    + (t.autoInstall() ? "开" : "关") + "/"
                    + (t.autoGrant() ? "开" : "关") + "/"
                    + (t.autoLaunch() ? "开" : "关"));
        }
    }

    // ------------------------------------------------------------- 包名查询 v3.1
    private void rebuildPkgList() {
        if (pkgListBox == null) return;
        pkgListBox.removeAllViews();
        java.util.List<String> pkgs = GuardState.thirdPartyPkgs;
        if (pkgs == null) {
            pkgListHint.setText("尚未查询：连接 ADB 后点上方按钮，列出电视里所有第三方应用包名");
            pkgListHint.setVisibility(View.VISIBLE);
            if (btnPkgMore != null) btnPkgMore.setVisibility(View.GONE);
            return;
        }
        if (pkgs.size() == 0) {
            pkgListHint.setText("查询完成：没有读到第三方应用包名 · " + GuardState.pkgQueryHint);
            pkgListHint.setVisibility(View.VISIBLE);
            if (btnPkgMore != null) btnPkgMore.setVisibility(View.GONE);
            return;
        }
        pkgListHint.setText("共 " + pkgs.size() + " 个 · " + GuardState.pkgQueryHint
                + "（点包名右侧「复制」即可粘贴到任务包名）");
        pkgListHint.setVisibility(View.VISIBLE);

        int limit = showAllPkgs ? pkgs.size() : Math.min(PKG_PREVIEW, pkgs.size());
        for (int i = 0; i < limit; i++) {
            final String pkg = pkgs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(pkg);
            name.setTextSize(wide ? 14 : 12);
            name.setTextColor(TEXT);
            name.setSingleLine(true);
            name.setHorizontallyScrolling(true);
            name.setPadding(0, dp(6), dp(4), dp(6));
            // 点包名本身也复制，减少一步操作
            name.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { copyPkg(pkg); }
            });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            name.setLayoutParams(nlp);
            row.addView(name);
            row.addView(miniButton("复制", new View.OnClickListener() {
                public void onClick(View v) { copyPkg(pkg); }
            }, false), lpFixed());
            pkgListBox.addView(row);
        }
        if (btnPkgMore != null) {
            int folded = pkgs.size() - limit;
            if (folded > 0) {
                btnPkgMore.setVisibility(View.VISIBLE);
                btnPkgMore.setText("展开其余 " + folded + " 个包名（共 " + pkgs.size() + " 个）");
            } else if (showAllPkgs && pkgs.size() > PKG_PREVIEW) {
                btnPkgMore.setVisibility(View.VISIBLE);
                btnPkgMore.setText("收起，只看前 " + PKG_PREVIEW + " 个");
            } else {
                btnPkgMore.setVisibility(View.GONE);
            }
        }
    }

    private void copyPkg(String pkg) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("包名", pkg));
            GuardLog.i(this, "已复制包名：" + pkg + "（可粘贴到任务包名输入框）");
            android.widget.Toast.makeText(this, "已复制包名：" + pkg,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            GuardLog.i(this, "复制失败：" + t);
        }
    }

    // ------------------------------------------------------------- 主题构件
    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private GradientDrawable round(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 0, 0, 14));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }

    private LinearLayout.LayoutParams editLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(4));
        return p;
    }

    private void styleEdit(EditText e) {
        e.setBackground(round(CARD_IN, LINE, 1, 10));
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setTextSize(wide ? 15 : 13);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(wide ? 15 : 12);
        t.setTextColor(SUB);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = miniButton(s, l, false);
        buttons.add(b);
        return b;
    }

    /** 任务列表里的按钮不进全局按钮表，避免重建列表时残留旧引用。 */
    private Button miniButton(String s, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(wide ? 14 : 11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setOnClickListener(l);
        b.setTag(Boolean.valueOf(primary));
        styleButton(b, primary, true);
        return b;
    }

    /** v3.4：任务卡片里的紧凑按钮（编辑 / 停用 / 删除 / 默认），比普通按钮更小。 */
    private Button taskButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(wide ? 12 : 10);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setPadding(dp(2), dp(4), dp(2), dp(4));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(l);
        b.setTag(Boolean.FALSE);
        styleButton(b, false, l != null);
        return b;
    }

    private void primary(Button b) { b.setTag(Boolean.TRUE); }

    private void styleButton(Button b, boolean primary, boolean enabled) {
        if (!enabled) {
            b.setBackground(round(CARD_IN, 0, 0, 10));
            b.setTextColor(DISABLED_TEXT);
        } else if (primary) {
            b.setBackground(round(BRAND, 0, 0, 10));
            b.setTextColor(ON_BRAND);
        } else {
            b.setBackground(round(CARD_IN, LINE, 1, 10));
            b.setTextColor(TEXT);
        }
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(0, dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout.LayoutParams lpLast() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(0, dp(4), 0, dp(4));
        return p;
    }

    /** 固定宽度的按钮参数：用于包名行的「复制」按钮，不随包名长短伸缩。 */
    private LinearLayout.LayoutParams lpFixed() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(4), dp(2), 0, dp(2));
        return p;
    }

    /** v3.4：任务卡片紧凑按钮参数：固定窄宽、靠左排列，不再平分占满整行。 */
    private LinearLayout.LayoutParams lpTaskBtn() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            dp(wide ? 66 : 52), ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(2), dp(6), dp(2));
        return p;
    }

    private TextWatcher watcher(final EditText target) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (target == etDevices && s != null) {
                    String cur = s.toString();
                    if (cur.indexOf('\n') >= 0 || cur.indexOf('\r') >= 0
                            || cur.indexOf('；') >= 0) {
                        String fixed = cur.replace('\r', ';').replace('\n', ';')
                                          .replace('；', ';');
                        int pos = etDevices.getSelectionStart();
                        s.replace(0, s.length(), fixed);
                        etDevices.setSelection(Math.min(pos, fixed.length()));
                        return;
                    }
                }
                scheduleSave();
            }
        };
    }

    private void scheduleSave() {
        if (etDevices == null) return;
        etDevices.removeCallbacks(saveTask);
        etDevices.postDelayed(saveTask, 400);
    }

    private void savePrefs() {
        if (etDevices == null || etInterval == null || etApkUrl == null) return;
        etDevices.removeCallbacks(saveTask);
        GuardPrefs p = new GuardPrefs(this);
        String d = etDevices.getText().toString().trim();
        if (d.length() > 0) p.setDevicesRaw(d);
        try {
            int v = Integer.parseInt(etInterval.getText().toString().trim());
            if (v < 3) v = 3;
            if (v > 600) v = 600;
            p.setIntervalSec(v);
        } catch (Exception ignored) {}
        p.setApkUrl(etApkUrl.getText().toString());
    }

    private void refresh() {
        if (tvStatus == null) return;
        boolean err = GuardState.isError();
        String head = !GuardState.running ? "○ 守护未启动"
                : (GuardState.adbOk ? "● 守护运行中" : "● 守护等待 ADB 连接");
        SpannableString sp = new SpannableString(head + "\n" + GuardState.status);
        sp.setSpan(new ForegroundColorSpan(err ? DANGER : BRAND_LIGHT),
                   head.length() + 1, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvStatus.setText(sp);
        tvDetail.setText(GuardState.detail);
        tvDetail.setVisibility(GuardState.detail.length() > 0 ? View.VISIBLE : View.GONE);

        if (btnStart != null) {
            boolean ok = GuardState.adbOk;
            btnStart.setEnabled(ok);
            btnInstall.setEnabled(ok);
            btnStart.setText(ok ? "启动守护" : "启动守护（先连接 ADB）");
        }
        // v3.5：未连 ADB 时「查询电视应用包名」按钮灰色不可点
        if (btnQueryPkgs != null) {
            btnQueryPkgs.setEnabled(GuardState.adbOk);
            btnQueryPkgs.setText(GuardState.adbOk
                    ? "查询电视应用包名" : "查询电视应用包名（先连接 ADB）");
        }
        if (btnDisconnect != null) {
            btnDisconnect.setEnabled(!GuardState.disconnected);
            btnDisconnect.setText(GuardState.disconnected ? "已断开 ADB" : "断开 ADB");
        }
        if (btnInstall != null) {
            int installed = 0;
            for (int i = 0; i < tasks.size(); i++) {
                if (GuardState.taskInstalled(tasks.get(i).id) == 1) installed++;
            }
            btnInstall.setText(installed > 0 && installed == tasks.size()
                    ? "任务已安装 · 可跳过" : "安装任务到电视");
            btnInstall.setTag(Boolean.valueOf(installed != tasks.size()));
        }
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            styleButton(b, Boolean.TRUE.equals(b.getTag()), b.isEnabled());
        }
        refreshTaskStatuses();

        StringBuilder m = new StringBuilder();
        m.append("任务 ").append(tasks.size()).append(" 个 · 累计检测 ")
         .append(GuardState.checks).append(" 次 · 拉起 ")
         .append(GuardState.relaunches).append(" 次 · 连接失败 ")
         .append(GuardState.fails).append(" 次");
        if (GuardState.reinstalls > 0) {
            m.append(" · 自动补装 ").append(GuardState.reinstalls).append(" 次");
        }
        if (GuardState.lastCheck > 0) {
            m.append("\n最近检测 ").append(new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(GuardState.lastCheck)));
        }
        m.append("\nADB ").append(GuardState.adbOk ? "已连通" : "未连通");
        if (GuardState.lastConnect > 0) {
            m.append(" · 最近连通 ").append(new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(GuardState.lastConnect)));
        }
        int probes = GuardState.probeCount + GuardState.rounds;
        if (probes > 0) m.append(" · 连通检测 ").append(probes).append(" 次");
        if (GuardState.lastProbe > 0) {
            m.append(" · 最近连通检测 ").append(new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(GuardState.lastProbe)));
        }
        tvMeta.setText(m.toString());

        int total = GuardLog.size();
        tvLog.setText(GuardLog.text(showAllLog ? 0 : LOG_PREVIEW));
        int folded = total - LOG_PREVIEW;
        if (folded > 0) {
            btnLogMore.setVisibility(View.VISIBLE);
            btnLogMore.setText(showAllLog
                    ? "收起，只看最新 " + LOG_PREVIEW + " 行"
                    : "展开其余 " + folded + " 行（共 " + total + " 条）");
        } else {
            btnLogMore.setVisibility(View.GONE);
        }
        rebuildPkgList();
    }
}
