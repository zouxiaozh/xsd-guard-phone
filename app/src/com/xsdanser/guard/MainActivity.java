package com.xsdanser.guard;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
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
import java.util.Locale;

/**
 * 纯代码布局（无 XML 布局资源）：状态卡 + 配置卡 + 操作按钮 + 倒序日志 + 说明。
 * v2.12：统一深色主题（深蓝灰底 / 青绿主色 / 浅灰蓝文字），圆角卡片与按钮，
 * 主操作用品牌色、次要操作用中性色，红色只留给错误状态；手机与 Android TV 通用。
 * v2.13：检测到电视已装学时兑时，「安装」按钮降为次要色并提示可跳过，步骤文字同步标注。
 * v2.14：说明文字补充「守护中会检测学时兑是否被删、被删自动装回并唤醒」，统计栏显示补装次数。
 */
public class MainActivity extends Activity {
    /** 运行日志默认只展示最新 10 行，其余折叠在「展开」按钮后。 */
    private static final int LOG_PREVIEW = 10;

    // ---- 主题配色：同一色系，主色仅用于可点的主操作与状态强调 ----
    private static final int BG = 0xFF0E1720;          // 页面底色
    private static final int CARD = 0xFF16232E;        // 卡片
    private static final int CARD_IN = 0xFF1D2E3A;     // 输入框 / 次要按钮
    private static final int LINE = 0xFF2A3D4A;        // 描边 / 分隔
    private static final int TEXT = 0xFFE8F1F4;        // 主文字
    private static final int SUB = 0xFF93AAB6;         // 次要文字
    private static final int LOG_TEXT = 0xFFB6C8D2;    // 日志正文
    private static final int BRAND = 0xFF14B8A6;       // 品牌青绿（主按钮）
    private static final int BRAND_LIGHT = 0xFF2DD4BF; // 小标题 / 状态强调
    private static final int DANGER = 0xFFEF4444;      // 仅错误状态
    private static final int ON_BRAND = 0xFF062A26;    // 青绿底上的文字
    private static final int DISABLED_TEXT = 0xFF647C88;

    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvMeta;
    private TextView tvLog;
    private EditText etDevices;
    private EditText etInterval;
    private EditText etApkUrl;
    private Button btnLogMore;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStart;
    private Button btnInstall;
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
        // 编译用的 framework 桩较旧，状态栏着色改反射调用，真机 API21+ 生效
        try {
            java.lang.reflect.Method m = getWindow().getClass()
                    .getMethod("setStatusBarColor", int.class);
            m.invoke(getWindow(), Integer.valueOf(BG));
        } catch (Throwable ignored) {}

        // 调试/临时切换入口：am start -n com.xsdanser.guard/.MainActivity -e device IP:端口
        Intent launch = getIntent();
        if (launch != null) {
            String override = launch.getStringExtra("device");
            if (override != null && override.trim().length() > 0
                    && override.lastIndexOf(':') > 0) {
                new GuardPrefs(this).setDevicesRaw("临时设备|" + override.trim() + "|1");
            }
        }
        GuardLog.load(this);

        int sw = getResources().getDisplayMetrics().widthPixels;
        wide = sw > 1200;                       // 电视 / 平板：收窄内容并放大字号
        int pad = wide ? (int) (sw * 0.16) : dp(18);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, dp(16), pad, dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        // ---- 配置卡 ----
        LinearLayout cfg = card();
        cfg.addView(label("设备列表（名称|IP:端口[,备用地址]|1开0关；多台用分号隔开）"));
        etDevices = new EditText(this);
        // 只显示一行：载入时把历史的多行配置折叠成分号，多台设备仍可编辑、不丢数据
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

        cfg.addView(label("学时兑 App 下载网址（安装时先从这里下载，再经 ADB 装到电视）"));
        etApkUrl = new EditText(this);
        etApkUrl.setMaxLines(1);
        etApkUrl.setLines(1);
        etApkUrl.setHorizontallyScrolling(true);
        etApkUrl.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        etApkUrl.setText(new GuardPrefs(this).apkUrl());
        styleEdit(etApkUrl);
        cfg.addView(etApkUrl, editLp());
        root.addView(cfg, cardLp());

        // 三个输入框内容一变就自动保存，不必再点「启动守护」才生效
        etDevices.addTextChangedListener(watcher(etDevices));
        etInterval.addTextChangedListener(watcher(etInterval));
        etApkUrl.addTextChangedListener(watcher(etApkUrl));

        // v2.9：只保留主流程三个按钮（连接 → 安装 → 启动守护）。
        // 权限开通 / 单次复核 / 本机电池优化白名单已并入「安装」与「启动守护」自动执行。
        TextView op = label("操作顺序：① 连接 ADB → ② 安装学时兑（电视已装可跳过）→ ③ 启动守护");
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
        btnInstall = button("安装学时兑到电视", new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                GuardService.start(MainActivity.this, GuardService.ACTION_INSTALL);
                GuardLog.i(MainActivity.this, "已请求安装学时兑到电视，请查看下方日志");
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

        // v2.10：运行日志与说明文字统一放在全部按钮下面；日志正文比说明文字小
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
            "使用步骤：① 填好设备列表与下载网址 → ② 连接 ADB → ③ 安装学时兑（电视已装可跳过）→ ④ 启动守护。\n"
          + "首次连接若电视弹出「允许 USB 调试吗」，请勾选「始终允许」再点允许；\n"
          + "App 会循环检测勾选是否生效，生效后才解锁后续功能（最多约 12 分钟）。\n"
          + "连通后每约 10 秒检测一次；点「断开 ADB」会停止守护与检测、锁定操作且不再自动重连，需重新连接。\n"
          + "电视已装学时兑时可跳过安装，直接启动守护（自动补齐悬浮窗 / 电池白名单 / 自启 / 防休眠并复核）；需升级再点安装按钮重装。\n"
          + "守护运行中会一直确认学时兑还在不在：被卸载或被电视管家清理掉，会自动下载安装包装回并唤醒，装回后守护继续。\n"
          + "打开 App 不会自动连接或守护；停止守护后仍每 10 秒检测连接（未连接时才退出）。\n"
          + "注意：多数盒子同一时间只允许一个 ADB 连接，用本 App 时请停掉电脑上的守护脚本。");
        hint.setLayoutParams(cardLp());
        root.addView(hint);

        setContentView(sv);
        refresh();
        // v2.4：打开 App 不再自动连接 / 自动守护，一切操作以「连接 ADB」成功为前提。
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
        savePrefs();                 // 退出页面时兜底，配置绝不丢
        GuardLog.setListener(null);
        GuardState.onChange = null;
        super.onPause();
    }

    // ------------------------------------------------------------- 主题构件
    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 统一圆角色块：卡片、输入框、按钮共用，保证颜色与圆角一致。 */
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
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(wide ? 15 : 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setOnClickListener(l);
        b.setTag(Boolean.FALSE);       // 默认次要按钮
        buttons.add(b);
        return b;
    }

    private void primary(Button b) { b.setTag(Boolean.TRUE); }

    /** 主操作青绿、次要操作中性、禁用降饱和；红色只用于状态错误。 */
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
        p.setMargins(0, dp(4), dp(8), dp(4));
        return p;
    }

    private LinearLayout.LayoutParams lpLast() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(0, dp(4), 0, dp(4));
        return p;
    }

    /** 两个输入框共用的自动保存监听器；设备列表额外把换行规范成分号，保证只显示一行。 */
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
                        return;  // s.replace 会再次回调，那一轮再落盘
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
        etDevices.removeCallbacks(saveTask);   // 立即保存时取消防抖，避免重复写
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
        // 第一行用主文字色，第二行状态用青绿（错误时红），深色底下对比清晰
        sp.setSpan(new ForegroundColorSpan(err ? DANGER : BRAND_LIGHT),
                   head.length() + 1, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvStatus.setText(sp);
        tvDetail.setText(GuardState.detail);
        tvDetail.setVisibility(GuardState.detail.length() > 0 ? View.VISIBLE : View.GONE);

        // v2.4：ADB 连通后才允许启动守护 / 安装；「连接 ADB」始终可点
        if (btnStart != null) {
            boolean ok = GuardState.adbOk;
            btnStart.setEnabled(ok);
            btnInstall.setEnabled(ok);
            btnStart.setText(ok ? "启动守护" : "启动守护（先连接 ADB）");
        }
        // v2.11：断开后按钮置灰并改名，点「连接 ADB」后自动恢复
        if (btnDisconnect != null) {
            btnDisconnect.setEnabled(!GuardState.disconnected);
            btnDisconnect.setText(GuardState.disconnected ? "已断开 ADB" : "断开 ADB");
        }
        // v2.13：检测到电视已装学时兑时，安装这一步可跳过 —— 按钮降为次要色并明确提示
        if (btnInstall != null) {
            boolean installed = GuardState.tvInstalled == 1;
            btnInstall.setText(installed ? "学时兑已安装 · 可跳过" : "安装学时兑到电视");
            btnInstall.setTag(Boolean.valueOf(!installed));
        }
        // v2.12：按钮配色随可用状态刷新，禁用项自动降饱和
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            styleButton(b, Boolean.TRUE.equals(b.getTag()), b.isEnabled());
        }

        StringBuilder m = new StringBuilder();
        m.append("累计检测 ").append(GuardState.checks).append(" 次 · 拉起 ")
         .append(GuardState.relaunches).append(" 次 · 连接失败 ")
         .append(GuardState.fails).append(" 次");
        if (GuardState.reinstalls > 0) {
            m.append(" · 自动补装 ").append(GuardState.reinstalls).append(" 次");
        }
        if (GuardState.lastCheck > 0) {
            m.append("\n最近检测 ").append(new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(GuardState.lastCheck)));
        }
        // v2.10：界面与日志只写结论，不再说明检测方式
        m.append("\nADB ").append(GuardState.adbOk ? "已连通" : "未连通");
        if (GuardState.lastConnect > 0) {
            m.append(" · 最近连通 ").append(new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(GuardState.lastConnect)));
        }
        // v2.6：连通检测心跳（监视器每 10 秒一次 + 守护每轮一次），让结果在界面上可见
        int probes = GuardState.probeCount + GuardState.rounds;
        if (probes > 0) {
            m.append(" · 连通检测 ").append(probes).append(" 次");
        }
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
            btnLogMore.setVisibility(View.GONE);   // 不足 10 行时无需折叠入口
        }
    }
}
