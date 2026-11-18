package com.xsdanser.guard;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 手机 / 电视侧常驻守护：前台服务 + 轮询，通过内嵌 ADB 客户端检查盒子上的
 * QuizOverlayService，掉了就发 v60 静默拉起命令。被守护设备侧 APK 完全不用改。
 *
 * v2：错误中文可读、日志倒序、判活与拉起多策略、多地址、兼容 Android 5.0+ / TV。
 * v2.3：ADB 连接成功后守护才生效；未连通时每约 10 秒检测连通性，恢复后自动继续。
 * v2.4：改为手动连接：点「连接 ADB」成功后各功能解锁；连通性由每 10 秒的监视 /
 *        守护轮询维护，断开时自动锁定操作，恢复后自动解锁。
 * v2.5：首次授权时检测电视上的「始终允许」：未勾选（或弹窗未确认）就循环等待，
 *        勾选生效（密钥被电视持久记住）后才判定连接成功并解锁后续功能。
 * v2.6：每 10 秒给出连通检测结果（日志 / 状态 / 通知 / 界面）；拉起前与安装后
 *        自动补放悬浮窗权限 —— 修掉「重装后权限丢失导致服务被系统拒绝前台化、
 *        拉起始终无效」的问题。
 * v2.7：连接日志改为明确结论「ADB 连接成功 / 未成功」；取消开机自启、防休眠
 *        开关（手机端常开）；启动守护前自动检测并补齐学时兑必需权限
 *        （悬浮窗 / 电池白名单 / 自启 / 防休眠），安装后同样自动开通。
 * v2.9：取消「保活设置」「单次检测」「开通权限」三个按钮；本机电池优化白名单、
 *        完整判活复核与学时兑权限开通全部并入安装 / 启动守护流程自动执行。
 * v2.10：运行日志与说明文字移到全部按钮下方，日志字号小于说明文字；
 *         日志与状态行不再说明检测方式，只写结论。
 * v2.11：新增「断开 ADB」：停守护 + 停每 10 秒连通检测 + 锁定操作，且不再自动重连，
 *         必须再次点「连接 ADB」才恢复；连接 / 判活 / 监视各入口都检查该标志。
 * v2.13：连接成功后自动检测电视是否已装学时兑；已装时「安装」这一步可跳过，
 *         安装按钮在界面上降为可跳过提示，直接点「启动守护」即可。
 * v2.14：守护运行中每轮都确认电视上的学时兑还在不在；一旦发现被卸载 / 被清理，
 *         自动下载安装包 → 推送安装 → 补齐权限 → 唤醒，装回后守护继续。
 * v2.14.2：补装失败的冷却改为 60/120/240/480 秒（此前首轮即 120 秒），冷却期间每轮
 *          打印「还剩多少秒」，且不再每轮重复刷「转自动补装」；前因并入补装首行日志。
 * v3.0：通用守护 —— 不再写死学时兑。每个守护任务自带包名、显示名、安装 / 授权 / 拉起开关，
 *       安装包来源二选一：固定下载网址，或在 App 里从手机本地选择 APK 后经 ADB 推送安装。
 */
public class GuardService extends Service {
    public static final String ACTION_START = "com.xsdanser.guard.START";
    public static final String ACTION_STOP = "com.xsdanser.guard.STOP";
    public static final String ACTION_CHECK = "com.xsdanser.guard.CHECK";
    public static final String ACTION_INSTALL = "com.xsdanser.guard.INSTALL";
    public static final String ACTION_GRANT = "com.xsdanser.guard.GRANT";
    public static final String ACTION_CONNECT = "com.xsdanser.guard.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xsdanser.guard.DISCONNECT";
    /** v3.1：查询电视里所有第三方应用包名（供复制后填入任务）。 */
    public static final String ACTION_LIST_PKGS = "com.xsdanser.guard.LIST_PKGS";

    private static final String CHANNEL = "xsd_guard";
    private static final int NOTI_ID = 1;
    /** 默认任务仍是学时兑：老用户升级后无需重新配置。 */
    private static final String DEFAULT_PKG = "com.xsdanser.tv";
    private static final String APK_ASSET = "XsdAnser-v30-ui.apk";
    private static final String APK_URL = GuardPrefs.DEFAULT_APK_URL;
    /** ADB 未连通时的连通性检测节律（秒）；连通后回到用户配置的检测间隔。 */
    private static final int ADB_RETRY_SEC = 10;
    /** 「始终允许」等待轮数：每轮最长约 90 秒（电视授权弹窗等待），走完仍无结果才判失败。 */
    private static final int AUTH_WAIT_ROUNDS = 8;
    /** 提交公钥后这么短时间内即放行，视为设备自动放行（无授权弹窗，无需勾选）。 */
    private static final long AUTH_AUTO_GRANT_MS = 2500;
    /** v2.14：自动补装的冷却基数；连续失败时按 1/2/4/8 倍拉长，避免网络差时每轮都重试。 */
    private static final long HEAL_COOLDOWN_MS = 60000L;

    /** v2.14：每台设备的补装状态（只由守护线程读写）。 */
    private static final class Heal {
        long last;          // 上次补装开始时间
        int fails;          // 连续失败次数
        boolean reported;   // 过渡用：v2.14.1 起冷却每轮都写剩余秒数，此字段不再阻止日志
        long nextAt;        // 下次允许重试的时间点（毫秒）
    }
    private final java.util.HashMap<String, Heal> heals = new java.util.HashMap<String, Heal>();

    /** v3：当前生效的任务列表（来自配置，UI 改动后下一轮立即读取）。 */
    private List<GuardTask> tasks() {
        return new GuardPrefs(this).tasks();
    }

    private GuardTask taskById(String id) {
        if (id == null) return null;
        List<GuardTask> all = tasks();
        for (int i = 0; i < all.size(); i++) {
            if (id.equals(all.get(i).id)) return all.get(i);
        }
        return null;
    }

    /** 指定任务时只处理它；未指定则处理全部已启用任务。 */
    private List<GuardTask> selectedTasks(String id) {
        List<GuardTask> out = new ArrayList<GuardTask>();
        List<GuardTask> all = tasks();
        for (int i = 0; i < all.size(); i++) {
            GuardTask t = all.get(i);
            if (!t.enabled) continue;
            if (id == null || id.length() == 0 || id.equals(t.id)) out.add(t);
        }
        return out;
    }

    private static String labelOf(GuardTask t) {
        return t == null ? "目标 App" : t.label;
    }

    private volatile boolean alive;
    private volatile boolean maintenance;
    private volatile boolean monitorOn;
    /** v2.11：手动断开后为 true，禁止一切自动探测 / 守护 / 重连，直到再次点「连接 ADB」。 */
    private static volatile boolean disconnected;
    private Thread worker;
    private Thread monitor;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTI_ID, buildNotification("守护启动中"));
        acquireLocks();
        GuardLog.i(this, "服务创建");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = (i == null || i.getAction() == null) ? ACTION_START : i.getAction();
        final String taskId = (i == null) ? null : i.getStringExtra("taskId");
        // v2.9：原「保活设置」按钮已取消 —— 安装 / 启动守护时自动检测并申请本机电池白名单
        if (ACTION_INSTALL.equals(a) || ACTION_START.equals(a)) {
            ensurePhoneKeepAlive(ACTION_INSTALL.equals(a) ? "[安装] " : "[启动守护] ");
        }
        if (ACTION_STOP.equals(a)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (ACTION_DISCONNECT.equals(a)) {
            disconnectAdb();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(a)) {
            runConnect();
            return START_STICKY;
        }
        if (ACTION_INSTALL.equals(a) || ACTION_GRANT.equals(a)) {
            runMaintenance(a, taskId);
            return START_STICKY;
        }
        if (ACTION_LIST_PKGS.equals(a)) {
            runListPackages();
            return START_STICKY;
        }
        if (ACTION_CHECK.equals(a)) {
            if (!GuardState.adbOk) {
                GuardLog.i(this, "ADB 未连接，已取消单次检测：请先点「连接 ADB」");
                GuardState.set("ADB 未连接", "请先点击「连接 ADB」，成功后再执行检测");
                updateNoti("等待连接 ADB");
                return START_STICKY;
            }
            if (worker != null && worker.isAlive()) {
                GuardLog.i(this, "守护线程正在跑，忽略单次检测请求");
            } else {
                startWorker(true);
            }
            return START_STICKY;
        }
        // v2.4：守护以「连接 ADB」成功为前提，不再打开即自动连接 / 自动守护
        if (!GuardState.adbOk) {
            GuardLog.i(this, "ADB 未连接，暂不启动守护：请先点「连接 ADB」，成功后再启动");
            GuardState.set("ADB 未连接", "请先点击「连接 ADB」，成功后即可启动守护");
            updateNoti("等待连接 ADB");
            return START_NOT_STICKY;
        }
        startWorker(false);
        return START_STICKY;
    }

    private synchronized void startWorker(final boolean once) {
        if (disconnected) return;               // v2.11：手动断开后不再起守护线程
        if (worker != null && worker.isAlive()) return;
        alive = true;
        worker = new Thread(new Runnable() {
            public void run() {
                if (once) {
                    try { cycle(); }
                    catch (Throwable t) { GuardLog.i(GuardService.this, "单次检测异常：" + t); }
                    GuardState.lastCheck = System.currentTimeMillis();
                    GuardState.running = false;
                    GuardState.set("单次检测完成", "");
                    return;
                }
                // v3：启动守护先按任务逐个检测并补齐目标 App 必需权限，缺的自动开通
                try {
                    GuardLog.i(GuardService.this, "启动守护：先检测各任务的权限（悬浮窗 / 电池白名单 / 自启 / 防休眠）");
                    ensureAllPermissions();
                } catch (Throwable t) {
                    GuardLog.i(GuardService.this, "权限自检异常（不阻塞守护）：" + t);
                }
                // v2.9：原「单次检测」按钮已取消 —— 启动守护首轮即完整复核（掉了自动拉起）
                GuardLog.i(GuardService.this, "启动守护：首轮自动执行完整检测（服务状态 / 拉起 / 复核）");
                loop();
            }
        }, "xsd-guard");
        worker.start();
    }

    private void loop() {
        GuardState.running = true;
        GuardState.set("等待首轮检测", "");
        GuardLog.i(this, "守护线程启动");
        while (alive && !disconnected) {
            // 维护（安装 / 授权）进行时，守护循环让出 ADB 连接，避免抢线
            if (maintenance) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                continue;
            }
            try { cycle(); }
            catch (Throwable t) { GuardLog.i(this, "本轮异常：" + t); }
            GuardState.lastCheck = System.currentTimeMillis();
            GuardState.fire();
            int sec = waitSec();
            for (int s = 0; s < sec && alive && !maintenance; s++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                // 页面上自动保存的新间隔若为更小值，或 ADB 状态变化后，提前结束等待尽快生效
                if (s + 1 >= waitSec()) break;
            }
        }
        GuardState.running = false;
        GuardState.set("已停止", "");
    }

    /** ADB 未连通时用较短的固定节律检测连通性；连通后回到用户配置的检测间隔。 */
    private int waitSec() {
        int sec = new GuardPrefs(this).intervalSec();
        return GuardState.adbOk ? sec : Math.min(sec, ADB_RETRY_SEC);
    }

    private void cycle() {
        if (disconnected) return;               // v2.11：手动断开后不再自动判活 / 重连
        GuardPrefs p = new GuardPrefs(this);
        List<GuardPrefs.Dev> devs = p.devices();
        List<GuardTask> ts = selectedTasks(null);
        if (ts.isEmpty()) {
            GuardState.adbOk = false;
            GuardState.set("没有启用任务", "请到「守护任务」里添加并启用至少一个目标 App");
            updateNoti("没有启用任务");
            return;
        }
        AdbCrypto crypto;
        try { crypto = loadCrypto(); }
        catch (Exception e) {
            TvCompat.Err x = TvCompat.explain(e, "本机密钥");
            GuardLog.i(this, "密钥不可用：" + x.hint);
            GuardState.set("密钥异常", x.hint);
            return;
        }
        boolean wasOk = GuardState.adbOk;
        int on = 0, ok = 0;
        for (int i = 0; i < devs.size(); i++) {
            GuardPrefs.Dev d = devs.get(i);
            if (!d.on) continue;
            on++;
            if (checkOne(d, crypto, ts)) ok++;
        }
        if (on == 0) {
            GuardState.adbOk = false;
            GuardState.set("未启用设备", "设备列表里没有开启（=1）的设备");
            return;
        }
        // v2.3：ADB 连通状态门控 —— 连通后守护（判活 + 拉起）才真正生效
        GuardState.adbOk = (ok > 0);
        GuardState.rounds++;
        GuardState.lastProbe = System.currentTimeMillis();
        GuardState.fire();
        // 每轮给出一条明确的连通结论，日志里能直接看到「每 10 秒检测一次」的结果
        // v2.14.3：去掉「第 N 轮检测：」前缀，界面日志只留结论
        GuardLog.i(this, "ADB "
                + (ok > 0 ? "连通正常（" + ok + "/" + on + "）" : "未连通（" + ok + "/" + on + "）"));
        if (ok > 0) {
            GuardState.lastConnect = System.currentTimeMillis();
            if (!wasOk) {
                GuardLog.i(this, "ADB 已连接（" + ok + "/" + on + " 台设备连通），自动守护开始生效");
                GuardState.fire();
            }
        } else if (wasOk) {
            GuardLog.i(this, "ADB 连接全部断开，暂停检测，每 " + ADB_RETRY_SEC
                    + " 秒检测连通性，恢复后自动继续守护");
            GuardState.set("等待 ADB 连接", "ADB 已断开，每 " + ADB_RETRY_SEC
                    + " 秒自动检测，连通后自动恢复守护");
            updateNoti("等待 ADB 连接");
        } else if (!"密钥异常".equals(GuardState.status)) {
            // 一直未连通：统一显示等待状态，具体原因保留在 detail（checkOne 已写入）
            String keep = GuardState.detail;
            GuardState.set("等待 ADB 连接", keep.length() > 0 ? keep
                    : "每 " + ADB_RETRY_SEC + " 秒自动检测，连接成功后自动开始守护");
            updateNoti("等待 ADB 连接");
        }
    }

    /** 检测一台设备上的全部启用任务；返回 ADB 是否连通（与任务服务状态无关）。 */
    private boolean checkOne(GuardPrefs.Dev d, AdbCrypto crypto, List<GuardTask> tasks) {
        String tag = "[" + d.name + "] ";
        String[] addrs = TvCompat.addresses(d.addr);
        if (addrs.length == 0) {
            GuardState.checks++;
            GuardState.fails++;
            GuardLog.i(this, tag + "设备地址无效，请填 IP:端口（例 192.168.31.9:5555）");
            GuardState.set("地址无效", "设备列表里填的地址无法识别：" + d.addr);
            updateNoti("地址无效 · " + d.name);
            return false;
        }
        // 连不上也算一次检测，界面不再出现「守护运行中但累计检测 0 次」
        GuardState.checks++;
        GuardState.fire();

        Throwable last = null;
        for (int a = 0; a < addrs.length; a++) {
            String addr = addrs[a];
            AdbClient c = null;
            try {
                c = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                c.connect(5000, 20000, 12000);
                GuardState.lastConnect = System.currentTimeMillis();
                if (addrs.length > 1)
                    GuardLog.i(this, tag + "已连接 " + addr + "（第 " + (a + 1) + " 个候选地址）");

                // 一条 ADB 连接里顺序处理所有任务，避免多任务互相抢盒子唯一连接
                for (int ti = 0; ti < tasks.size(); ti++) {
                    GuardTask t = tasks.get(ti);
                    if (!t.enabled) continue;
                    String ttag = tag + "[" + t.label + "] ";
                    String pm = null;
                    try { pm = c.shell("pm path " + t.pkg); } catch (Exception ignored) {}
                    int inst = TvCompat.installState(c, t.pkg, pm);
                    GuardState.setTaskInstalled(t.id, inst > 0 ? 1 : inst < 0 ? 2 : 0);
                    GuardState.fire();

                    if (inst < 0) {
                        // 目标 App 被卸载 / 被清理：按任务自己的开关决定是否补装
                        if (!t.autoInstall()) {
                            GuardLog.i(this, ttag + "目标 App 未安装（此任务未开启自动补装），本轮跳过");
                            GuardState.set("目标 App 未安装", t.label + " · " + d.name);
                        } else {
                            try { c.close(); } catch (Exception ignored) {}
                            c = null;
                            healReinstall(d, crypto, ttag, t);
                        }
                        // 补装 / 跳过都不算 ADB 断线：本台连接是通的
                        return true;
                    }
                    if (inst == 0) {
                        // 有的定制 ROM 输出格式特殊，判不出来时不补装，也不阻断其它任务
                        GuardLog.i(this, ttag + "未在电视上读到安装包信息，仍继续检测");
                        continue;
                    }
                    if (t.autoGrant()) ensureTvPermissions(c, ttag, t);

                    TvCompat.Probe pr = TvCompat.probe(c, t.pkg);
                    GuardState.setVia(pr.via);
                    if (pr.state == null) {
                        GuardLog.i(this, ttag + "服务状态无法判定，本轮跳过（ROM 输出格式特殊）");
                        GuardState.set("判活不可判定", t.label + " · 电视输出无法识别，下轮重试");
                        updateNoti("判活不可判定 · " + d.name);
                        continue;
                    }
                    if (pr.state.booleanValue()) {
                        // v2.14.3：界面/通知统一叫「守护运行中」
                        GuardLog.i(this, ttag + "守护运行中");
                        GuardState.set("守护运行中", t.label + " · " + d.name + " · " + addr);
                        updateNoti("守护运行中 · " + d.name);
                        continue;
                    }
                    if (!t.autoLaunch()) {
                        GuardLog.i(this, ttag + "服务未运行（此任务未开启自动拉起），本轮跳过");
                        GuardState.set("服务未运行", t.label + " · 未开启自动拉起");
                        continue;
                    }

                    GuardLog.i(this, ttag + "服务消失，执行静默拉起");
                    GuardState.set("正在拉起", t.label + " · " + d.name);
                    updateNoti("正在拉起 · " + d.name);
                    ensureTvPermissions(c, ttag, t);
                    String[] cmds = TvCompat.launchCommands(t.pkg);
                    for (int k = 0; k < cmds.length; k++) {
                        String out = null;
                        try { out = c.shell(cmds[k]); }
                        catch (Exception e) { GuardLog.i(this, ttag + "拉起命令异常，改试下一种"); }
                        try {
                            android.util.Log.i("XsdGuard", ttag + "拉起 " + (k + 1)
                                    + " 原始返回：" + oneLine(out));
                        } catch (Throwable ignored) {}
                        boolean failed = TvCompat.launchFailed(out);
                        GuardLog.i(this, ttag + "拉起 " + (k + 1) + "/" + cmds.length
                                + "：" + (failed ? "返回错误，改试下一种" : "已执行"));
                        if (!failed) break;
                    }
                    GuardState.relaunches++;
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return true; }

                    // 盒子同一时间只允许一个 ADB 客户端：复核前必须先释放当前连接
                    try { c.close(); } catch (Exception ignored) {}
                    c = null;
                    AdbClient c2 = null;
                    try {
                        c2 = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                        c2.connect(5000, 20000, 12000);
                        TvCompat.Probe after = TvCompat.probe(c2, t.pkg);
                        boolean ok = Boolean.TRUE.equals(after.state);
                        GuardLog.i(this, ttag + "复核：" + (ok ? "服务已恢复" : "服务仍未起，下轮继续"));
                        GuardState.set(ok ? "服务已恢复" : "等待下轮重试", t.label + " · " + d.name);
                    } catch (Exception e) {
                        GuardLog.i(this, ttag + "复核失败：" + TvCompat.explain(e, addr).hint);
                    } finally {
                        if (c2 != null) c2.close();
                    }
                    return true;
                }
                return true;
            } catch (Throwable e) {
                last = e;
                // 多个候选地址时继续试下一个；单地址则由下方统一给出中文提示
            } finally {
                if (c != null) c.close();
            }
        }

        TvCompat.Err x = TvCompat.explain(last, d.addr);
        GuardState.fails++;
        try { android.util.Log.i("XsdGuard", tag + "技术详情 " + TvCompat.tech(last)); }
        catch (Throwable ignored) {}
        GuardLog.i(this, tag + TvCompat.logLine(last, d.addr));
        GuardState.set(x.status, x.hint);
        updateNoti(x.status + " · " + d.name);
        return false;
    }

    // ------------------------------------------------------------- 自动补装（v2.14）
    /**
     * 守护运行期间发现任务目标 App 被删掉时：按该任务的安装包来源取出 APK、
     * 推送安装、补齐权限并唤醒。先用缓存包（快、断网也能装回），失败再按来源重新取包。
     * 失败按 60/120/240/480 秒冷却重试，避免网络不通时每轮都去抢电视那一个 ADB 连接。
     */
    private boolean healReinstall(GuardPrefs.Dev d, AdbCrypto crypto, String tag, GuardTask t) {
        String key = t.id + "|" + d.addr;
        Heal h = heals.get(key);
        if (h == null) {
            h = new Heal();
            heals.put(key, h);
        }
        long now = System.currentTimeMillis();
        long wait = HEAL_COOLDOWN_MS * (1L << Math.min(Math.max(h.fails - 1, 0), 3));
        if (h.last > 0 && now - h.last < wait) {
            long left = (wait - (now - h.last) + 999) / 1000;
            GuardLog.i(this, tag + "自动补装冷却中：还剩 " + left + " 秒重试（已连续失败 "
                    + h.fails + " 次）");
            GuardState.set("等待自动补装", t.label + " · " + d.name + " · 约 " + left + " 秒后重试");
            updateNoti("等待自动补装 · " + d.name);
            return false;
        }
        h.last = now;
        h.reported = false;
        GuardState.set("正在自动补装", t.label + " · " + d.name + " · 目标 App 已不在电视上");
        updateNoti("正在自动补装 " + t.label + " · " + d.name);
        GuardLog.i(this, tag + "检测到目标 App 已不在电视上（被卸载或被清理），开始自动补装："
                + "准备安装包（" + t.sourceName() + "）→ 推送 → 安装 → 补齐权限 → 唤醒");
        GuardState.fire();

        File cache = new File(getCacheDir(), t.cacheName());
        boolean ok = false;
        if (cache.isFile() && cache.length() > 100000) {
            GuardLog.i(this, tag + "先用手机里缓存的安装包（" + cache.length() + " 字节）");
            ok = installOne(d, crypto, t, cache, true);
            if (!ok) {
                GuardLog.i(this, tag + "缓存包没装成功，删掉缓存，改从"
                        + t.sourceName() + "重新获取");
                try { cache.delete(); } catch (Throwable ignored) {}
            }
        }
        if (!ok) {
            try {
                GuardLog.i(this, tag + "按" + t.sourceName() + "取安装包：" + t.sourceValue());
                GuardState.set("正在准备安装包", t.label + " · " + t.sourceName());
                File prepared = prepareApk(t);
                ok = installOne(d, crypto, t, prepared, false);
            } catch (Throwable e) {
                GuardLog.i(this, tag + "自动补装取包失败：" + TvCompat.logLine(e, t.sourceValue())
                        + "（网址请确认手机联网；本地 APK 请重新在 App 里选择一次）");
            }
        }

        if (ok) {
            h.fails = 0;
            GuardState.reinstalls++;
            GuardState.setTaskInstalled(t.id, 1);
            GuardState.set("已自动补装并唤醒", t.label + " · " + d.name + " · 已自动装回");
            updateNoti("已自动补装 " + t.label + " · " + d.name);
            GuardLog.i(this, tag + "自动补装完成：已重装、已补齐权限并唤醒，守护继续");
            GuardState.fire();
            return true;
        }
        h.fails++;
        GuardState.setTaskInstalled(t.id, 2);
        long next = HEAL_COOLDOWN_MS * (1L << Math.min(Math.max(h.fails - 1, 0), 3)) / 1000;
        GuardState.set("自动补装失败", t.label + " · 约 " + next + " 秒后自动重试");
        updateNoti("自动补装失败 · " + d.name);
        GuardLog.i(this, tag + "自动补装未成功，约 " + next + " 秒后自动重试（第 " + h.fails + " 次失败）");
        return false;
    }

    /**
     * v3：把任务指定的安装包准备好，落到本机缓存文件。
     * 固定网址 → 联网下载；本地 APK → 从 content:// / file:// URI 读取并复制。
     * 两种来源都支持无网重装（缓存存在时直接用缓存）。
     */
    private File prepareApk(GuardTask t) throws Exception {
        File cache = new File(getCacheDir(), t.cacheName());
        File tmp = new File(getCacheDir(), t.cacheName() + ".tmp");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (t.source == GuardTask.SRC_LOCAL) {
                if (t.localUri == null || t.localUri.trim().length() == 0)
                    throw new IOException("未选择手机本地 APK，请在 App 里点「选择本地 APK」");
                Uri uri = Uri.parse(t.localUri.trim());
                in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("本地 APK 读取失败，可能授权已失效，请重新选择一次");
                GuardLog.i(this, "从手机本地读取安装包：" + oneLine(t.localUri));
            } else {
                String url = (t.apkUrl == null || t.apkUrl.trim().length() == 0)
                        ? APK_URL : t.apkUrl.trim();
                GuardLog.i(this, "手机联网从固定网址获取安装包：" + url);
                GuardState.set("正在下载安装包", t.label);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                in = conn.getInputStream();
            }
            out = new FileOutputStream(tmp);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close(); out = null;
            in.close(); in = null;
            if (tmp.length() < 100000) throw new IOException("安装包只有 " + tmp.length()
                    + " 字节，明显不完整，已放弃");
            if (cache.exists() && !cache.delete()) throw new IOException("旧缓存无法覆盖");
            if (!tmp.renameTo(cache)) throw new IOException("缓存文件写入失败");
            GuardLog.i(this, "安装包已就绪：" + t.label + "（" + cache.length() + " 字节）");
            return cache;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------- 连接 ADB（手动）
    /**
     * 手动连接：按设备列表逐台建立 ADB 连接（带重试，兼顾授权弹窗期），
     * 成功后解锁安装 / 授权 / 守护，并开启连通性监视（每 10 秒检查是否仍在连接）。
     */
    private void runConnect() {
        disconnected = false;                // v2.11：手动点「连接 ADB」即解除断开锁定
        GuardState.disconnected = false;
        GuardState.tvInstalled = 0;          // v2.13：本次连接重新检测是否已装学时兑
        final boolean wasRunning = (worker != null && worker.isAlive());
        maintenance = true;
        alive = false;                       // 让守护循环让出连接，避免抢线
        if (worker != null) {
            worker.interrupt();
            try { worker.join(20000); } catch (InterruptedException e) {}
        }
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    GuardLog.i(GuardService.this, "开始按设备列表连接 ADB");
                    ok = connectAll();
                } catch (Throwable t) {
                    GuardLog.i(GuardService.this, "连接任务异常：" + t);
                } finally {
                    maintenance = false;
                    if (disconnected) {
                        // v2.11：连接过程中用户点了「断开 ADB」，本次连接结果作废
                        GuardState.adbOk = false;
                        GuardState.disconnected = true;
                        GuardState.set("已手动断开 ADB", "连接已取消：需要时重新点「连接 ADB」");
                        GuardLog.i(GuardService.this, "连接已取消：已手动断开 ADB");
                    } else if (ok) {
                        GuardState.adbOk = true;
                        GuardState.lastConnect = System.currentTimeMillis();
                        // v2.13：连上后先确认电视是否已装学时兑，已装则安装这一步可跳过
                        try {
                            maintenance = true;
                            probeTvInstalled();
                        } catch (Throwable t) {
                            GuardLog.i(GuardService.this, "目标 App 安装状态检测跳过：" + t);
                        } finally {
                            maintenance = false;
                        }
                        startMonitor();
                        GuardLog.i(GuardService.this, "ADB 连接成功（含「始终允许」确认），安装 / 授权 / 守护已解锁，"
                                + "每 " + ADB_RETRY_SEC + " 秒自动检查是否仍在连接");
                        if (wasRunning) { alive = true; startWorker(false); }
                        else GuardState.set("ADB 已连接", "可安装任务 App / 开通权限 / 启动守护");
                    } else {
                        GuardState.adbOk = false;
                        GuardState.set("ADB 未连接", "连接失败：请检查设备地址与 ADB 开关；"
                                + "若电视弹出「允许 USB 调试吗」，请勾选「始终允许」后点允许再重试");
                    }
                    // v2.7：日志以明确结论收尾，连接成功 / 未成功一眼可辨
                    if (!disconnected) {
                        GuardLog.i(GuardService.this, ok ? "ADB 连接成功" : "ADB 连接未成功");
                    }
                }
            }
        }, "xsd-guard-connect").start();
    }

    private boolean connectAll() {
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) {
            GuardState.set("ADB 未连接", "本机 ADB 密钥不可用，请先点「重置 ADB 密钥」再试");
            return false;
        }
        int on = countOn(devs);
        if (on == 0) {
            GuardState.set("ADB 未连接", "设备列表里没有开启（=1）的设备");
            return false;
        }
        int ok = 0;
        for (GuardPrefs.Dev d : devs) if (d.on && connectOne(d, crypto)) ok++;
        GuardLog.i(this, "ADB 连接结果：" + ok + "/" + on + " 台设备连通");
        return ok > 0;
    }

    /**
     * 连接一台设备（含「始终允许」检测，v2.5）：
     * - 密钥已被电视记住（此前勾选过）或设备免鉴权：直接连接成功；
     * - 设备自动放行（部分盒子无授权弹窗）：直接连接成功，无需勾选；
     * - 首次授权：电视弹「允许 USB 调试吗」，等用户勾选「始终允许」并点允许；
     *   只点「允许」没勾选（密钥不会记住）或弹窗没人确认时循环重试，勾选生效后才成功。
     */
    private boolean connectOne(GuardPrefs.Dev d, AdbCrypto crypto) {
        String tag = "[" + d.name + "] ";
        String[] addrs = TvCompat.addresses(d.addr);
        if (addrs.length == 0) {
            GuardLog.i(this, tag + "设备地址无效，请填 IP:端口（例 192.168.31.9:5555）");
            return false;
        }
        for (String addr : addrs) {
            int plainFails = 0;
            for (int round = 1; round <= AUTH_WAIT_ROUNDS; round++) {
                if (disconnected) return false;   // v2.11：断开后立即停止连接重试
                AdbClient c = null;
                boolean dialogShown = false;
                long grantMs = -1;
                Throwable err = null;
                try {
                    c = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                    c.connect(5000, 20000, 30000);
                    grantMs = c.authGrantMillis();
                    c.close();
                } catch (Throwable e) {
                    err = e;
                    dialogShown = (c != null) && c.authDialogShown();
                } finally {
                    if (c != null) { try { c.close(); } catch (Exception ignored) {} }
                }
                if (err == null) {
                    if (grantMs < 0) {
                        GuardLog.i(this, tag + "已连接 " + addr + "，本机密钥已被电视记住（无需再授权）");
                        return true;
                    }
                    if (grantMs < AUTH_AUTO_GRANT_MS) {
                        GuardLog.i(this, tag + "已连接 " + addr + "，设备自动放行（无需勾选「始终允许」）");
                        return true;
                    }
                    // 电视上人工点了「允许」：复核密钥是否被「始终允许」持久记住
                    if (verifyAlwaysAllow(addr, crypto)) {
                        GuardLog.i(this, tag + "已连接 " + addr + "，「始终允许」已确认（后续连接不再弹窗）");
                        return true;
                    }
                    GuardLog.i(this, tag + "电视上未勾选「始终允许」（只放行了刚才那一条连接，不会记住），"
                            + "已断开重试：请在电视弹窗中勾选「始终允许」后点允许（等待 "
                            + round + "/" + AUTH_WAIT_ROUNDS + " 轮）");
                    GuardState.set("等待勾选始终允许",
                            "电视弹窗里请勾选「始终允许」再点允许，检测到已勾选后自动继续");
                    updateNoti("等待电视勾选始终允许 · " + d.name);
                    sleepMs(2000);
                    continue;
                }
                if (dialogShown) {
                    // 弹窗已出现但尚未确认（超时或点了拒绝）：循环等待用户去电视上勾选
                    GuardLog.i(this, tag + "电视上的授权弹窗尚未确认（等待 " + round
                            + "/" + AUTH_WAIT_ROUNDS + " 轮）：请在电视上勾选「始终允许」后点允许");
                    GuardState.set("等待勾选始终允许",
                            "电视弹出「允许 USB 调试吗」，请勾选「始终允许」后点允许，勾选后自动继续");
                    updateNoti("等待电视勾选始终允许 · " + d.name);
                    sleepMs(2000);
                    continue;
                }
                // 普通连接失败（不在线 / 被占用等）：重试几轮后换下一个候选地址
                plainFails++;
                GuardLog.i(this, tag + "连接 " + addr + " 失败：" + TvCompat.logLine(err, addr)
                        + "（重试 " + round + "/" + AUTH_WAIT_ROUNDS + "）");
                if (plainFails >= 3) break;
                sleepMs(2500);
            }
            GuardLog.i(this, tag + "地址 " + addr + " 未连接成功，换下一候选地址");
        }
        GuardLog.i(this, tag + "所有候选地址均连接失败");
        return false;
    }

    /**
     * 复核「始终允许」是否生效：连接成功后断开，立刻新建一条快速连接。
     * 只有密钥被电视持久记住（勾选了「始终允许」）时，新连接才会直接放行；
     * 若电视只单次放行未勾选，设备会要求重新授权 —— 快速模式下会立即得到
     * ERR_NOT_TRUSTED，据此判定「未勾选」。快速模式不提交公钥，不会再次弹窗。
     */
    private boolean verifyAlwaysAllow(String addr, AdbCrypto crypto) {
        for (int i = 0; i < 3; i++) {
            AdbClient c = null;
            try {
                c = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                c.setFailFastAuth(true);
                c.connect(5000, 10000, 10000);
                return true;
            } catch (Throwable e) {
                if (AdbClient.ERR_NOT_TRUSTED.equals(String.valueOf(e.getMessage()))) {
                    return false;   // 设备明确不认这把密钥：未勾选「始终允许」
                }
                // 其它错误（如旧连接刚断、槽位未释放）：稍候重试
                sleepMs(2500);
            } finally {
                if (c != null) { try { c.close(); } catch (Exception ignored) {} }
            }
        }
        return false;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------------- 连通性监视
    /**
     * 连接过一次后开启：每 10 秒探测 ADB 是否仍在连接。
     * 断开 → 状态转「ADB 已断开」（页面操作自动锁定）；恢复 → 自动转回「ADB 已连接」。
     * 守护线程运行时由守护轮询自己维护连通状态（避免两个线程抢盒子唯一连接），此处让路。
     */
    private synchronized void startMonitor() {
        if (disconnected) return;            // v2.11：手动断开后不启动自动连通检测
        if (monitor != null && monitor.isAlive()) return;
        monitorOn = true;
        monitor = new Thread(new Runnable() {
            public void run() { monitorLoop(); }
        }, "xsd-guard-mon");
        monitor.start();
    }

    private void monitorLoop() {
        while (monitorOn && !disconnected) {
            try { Thread.sleep(ADB_RETRY_SEC * 1000); }
            catch (InterruptedException e) { return; }
            if (!monitorOn || disconnected) return;
            if (maintenance) continue;                          // 连接 / 安装 / 授权进行中，让路
            if (worker != null && worker.isAlive()) continue;   // 守护轮询自己会维护连通状态
            boolean ok = probeAdb();
            GuardState.lastProbe = System.currentTimeMillis();
            GuardState.probeCount++;
            if (ok && !GuardState.adbOk) {
                GuardState.adbOk = true;
                GuardState.lastConnect = System.currentTimeMillis();
                GuardLog.i(this, "ADB 已恢复连通，安装 / 授权 / 守护重新解锁（第 "
                        + GuardState.probeCount + " 次连通检测）");
                GuardState.set("ADB 已连接", "连接已自动恢复，可继续操作");
                updateNoti("ADB 已连接 · 可操作");
            } else if (!ok && GuardState.adbOk) {
                GuardState.adbOk = false;
                GuardLog.i(this, "ADB 已断开，安装 / 授权 / 守护已锁定，每 " + ADB_RETRY_SEC
                        + " 秒自动检测是否恢复（第 " + GuardState.probeCount + " 次连通检测）");
                GuardState.set("ADB 已断开", "每 " + ADB_RETRY_SEC + " 秒自动检测，恢复后自动解锁");
                updateNoti("ADB 已断开 · 每 " + ADB_RETRY_SEC + " 秒重试");
            } else if (ok) {
                // 用户要求：连通后每 10 秒给一次可见结果，日志里能直接看到心跳
                GuardLog.i(this, "ADB 连通正常（第 " + GuardState.probeCount
                        + " 次连通检测，每 " + ADB_RETRY_SEC + " 秒一次）");
                GuardState.set("ADB 已连接", "每 " + ADB_RETRY_SEC + " 秒检测一次，连接正常");
                updateNoti("ADB 已连接 · 检测正常");
            } else {
                GuardLog.i(this, "ADB 仍未连通（第 " + GuardState.probeCount
                        + " 次连通检测，每 " + ADB_RETRY_SEC + " 秒重试）");
                GuardState.set("ADB 未连通", "每 " + ADB_RETRY_SEC + " 秒自动重试连接");
                updateNoti("等待连接 ADB");
            }
            GuardState.fire();
        }
    }

    /** 轻量连通性探测：任一启用设备能建立 ADB 连接即视为仍在连接。 */
    private boolean probeAdb() {
        if (disconnected) return false;      // v2.11：手动断开后不再探测设备
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) return false;
        for (GuardPrefs.Dev d : devs) {
            if (!d.on) continue;
            for (String addr : TvCompat.addresses(d.addr)) {
                AdbClient c = null;
                try {
                    c = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                    c.connect(5000, 10000, 10000);
                    return true;
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
        }
        return false;
    }

    /**
     * v3：连接成功后逐任务检测目标 App 是否已安装，已装的安装步骤可跳过。
     * 只有明确读到 pm path 的 package: 才判已安装；连不上 / 定制 ROM 输出特殊时
     * 保持「未检测」，既不误报也不影响用户照常点安装。
     */
    private void probeTvInstalled() {
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) return;
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        List<GuardTask> ts = tasks();
        for (int ti = 0; ti < ts.size(); ti++) {
            GuardTask t = ts.get(ti);
            if (!t.enabled) continue;
            boolean installed = false;
            boolean reachable = false;
            for (GuardPrefs.Dev d : devs) {
                if (!d.on) continue;
                for (String addr : TvCompat.addresses(d.addr)) {
                    AdbClient c = null;
                    try {
                        c = new AdbClient(TvCompat.host(addr), TvCompat.port(addr), crypto);
                        c.connect(5000, 10000, 10000);
                        reachable = true;
                        String pm = sh(c, "pm path " + t.pkg);
                        if (TvCompat.packageInstalled(pm)) { installed = true; break; }
                    } catch (Throwable ignored) {
                    } finally {
                        if (c != null) c.close();
                    }
                }
                if (installed) break;
            }
            GuardState.setTaskInstalled(t.id, installed ? 1 : reachable ? 2 : 0);
            if (installed) {
                GuardLog.i(this, "电视已安装 " + t.label + "：可跳过安装，直接点「启动守护」");
            } else if (reachable) {
                GuardLog.i(this, "电视未安装 " + t.label + "：可点「安装任务到电视」");
            } else {
                GuardLog.i(this, "未确认 " + t.label + " 的安装状态，可先安装，已装也可直接启动守护");
            }
        }
        GuardState.fire();
    }

    // ------------------------------------------------------------- 本机保活（v2.9）
    /**
     * 原「保活设置」按钮已取消：安装 / 启动守护时自动检测守护 App 自身是否已加入
     * 系统电池优化白名单。未加入则弹一次系统申请（只弹一次，避免反复打扰）；
     * 弹窗被系统限制时只记日志，不阻塞安装 / 守护流程。
     */
    private void ensurePhoneKeepAlive(String tag) {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                GuardLog.i(this, tag + "本机保活：系统低于 Android 6.0，无电池优化白名单机制");
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            // 编译用的 TV framework 桩没有该方法，用反射兼容 Android 6.0+ 真机
            boolean ok = false;
            try {
                ok = Boolean.TRUE.equals(PowerManager.class
                        .getMethod("isIgnoringBatteryOptimizations", String.class)
                        .invoke(pm, getPackageName()));
            } catch (Throwable ignored) {
                GuardLog.i(this, tag + "本机保活：此系统不支持状态查询，转为直接申请白名单");
            }
            if (ok) {
                GuardLog.i(this, tag + "本机保活：已在电池优化白名单（守护不会被系统省电冻结）");
                return;
            }
            android.content.SharedPreferences sp = getSharedPreferences("guard", MODE_PRIVATE);
            if (sp.getBoolean("battery_asked", false)) {
                GuardLog.i(this, tag + "本机保活：尚未加入电池优化白名单（已申请过一次，不再重复弹窗）");
                return;
            }
            sp.edit().putBoolean("battery_asked", true).commit();
            Intent i = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            GuardLog.i(this, tag + "本机保活：未加入白名单，已自动弹出申请，请在弹窗里点「允许」");
        } catch (Throwable t) {
            GuardLog.i(this, tag + "本机保活：自动申请未生效，可在系统设置里关闭本 App 的省电限制：" + t);
        }
    }

    // ------------------------------------------------------------- 权限检测 / 补放（v2.7）
    /**
     * 启动守护前 / 安装后：逐台检测并补齐学时兑必需权限。
     * 悬浮窗（服务前台化必需）、电池白名单、自启（RECEIVE_BOOT_COMPLETED）、
     * 防休眠（WAKE_LOCK；当前学时兑未声明，靠前台服务保活，补放失败只提示不阻断）。
     */
    private void ensureAllPermissions() {
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        List<GuardTask> ts = selectedTasks(null);
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) return;
        for (int ti = 0; ti < ts.size(); ti++) {
            GuardTask t = ts.get(ti);
            if (!t.autoGrant()) continue;
            for (GuardPrefs.Dev d : devs) {
                if (!d.on) continue;
                String tag = "[" + d.name + "][" + t.label + "] ";
                for (String addr : TvCompat.addresses(d.addr)) {
                    AdbClient c = connectWithRetry(TvCompat.host(addr), TvCompat.port(addr), crypto);
                    if (c == null) continue;
                    try { ensureTvPermissions(c, tag, t); }
                    finally { c.close(); }
                    break;   // 本台已处理，换下一台
                }
            }
        }
    }

    /** 容错 shell：异常 / 无输出都返回 null，不影响后续检测项。 */
    private static String sh(AdbClient c, String cmd) {
        try { return c.shell(cmd); } catch (Throwable t) { return null; }
    }

    /**
     * 检测一台设备上的某个目标 App 必需权限，缺哪项就执行哪项开通命令并复核；
     * 最后输出一行汇总，已开通 / 已补放 / 未声明 一目了然。
     */
    private void ensureTvPermissions(AdbClient c, String tag, GuardTask t) {
        String pkg = t.pkg;
        int sdk = 0;
        try {
            sdk = Integer.parseInt(oneLine(sh(c, "getprop ro.build.version.sdk")).trim());
        } catch (Exception ignored) {}

        // 1) 悬浮窗：需要前台服务的 App 缺权限会被系统立刻回收
        String ov = sh(c, "appops get " + pkg + " SYSTEM_ALERT_WINDOW");
        boolean ovOk = ov != null && ov.toLowerCase().contains("allow");
        if (!ovOk) {
            sh(c, "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            if (sdk >= 30) sh(c, "appops set " + pkg + " SYSTEM_OVERLAY_WINDOW allow");
            ov = sh(c, "appops get " + pkg + " SYSTEM_ALERT_WINDOW");
            ovOk = ov != null && ov.toLowerCase().contains("allow");
            if (ovOk) GuardLog.i(this, tag + "已自动放行悬浮窗权限"
                    + "（目标 App 需要前台化，缺权限会被系统立刻回收）");
        }

        // 2) 电池白名单（doze 休眠时不杀目标 App）
        String wl = sh(c, "dumpsys deviceidle whitelist");
        boolean wlOk = wl != null && wl.contains(pkg);
        if (!wlOk) {
            sh(c, "dumpsys deviceidle whitelist +" + pkg);
            wl = sh(c, "dumpsys deviceidle whitelist");
            wlOk = wl != null && wl.contains(pkg);
            if (wlOk) GuardLog.i(this, tag + "已加入电池白名单");
        }

        // 3) + 4) 自启 / 防休眠：dumpsys package 里声明过的普通权限装完即授予；
        //    没出现的用 pm grant 尽力补放（App 未声明时会报错，仅记录不阻断）
        String dp = sh(c, "dumpsys package " + pkg);
        boolean bootOk = dp != null && dp.contains("android.permission.RECEIVE_BOOT_COMPLETED");
        boolean bootDeclared = bootOk;
        if (!bootOk) {
            String out = sh(c, "pm grant " + pkg + " android.permission.RECEIVE_BOOT_COMPLETED");
            bootOk = out == null || !TvCompat.launchFailed(out);
            if (bootOk) GuardLog.i(this, tag + "已开通自启权限（开机自启）");
        }
        boolean wakeOk = dp != null && dp.contains("android.permission.WAKE_LOCK");
        boolean wakeDeclared = wakeOk;
        if (!wakeOk) {
            String out = sh(c, "pm grant " + pkg + " android.permission.WAKE_LOCK");
            wakeOk = out == null || !TvCompat.launchFailed(out);
            if (wakeOk) GuardLog.i(this, tag + "已开通防休眠权限（WAKE_LOCK）");
        }

        // 5) 通知使用权（复活监听）：让系统长期绑定目标 App 的 RevivalListener 服务，
        //    进程被清后系统自动重绑 -> 秒级复活。Android 10+ 用 cmd notification allow_listener，
        //    低版本写 Settings.Secure。目标 App 未声明该服务时命令无副作用，仅记录不阻断。
        String revivalCls = pkg + ".RevivalListener";
        String revivalComp = pkg + "/" + revivalCls;   // 授权命令需要的 包名/组件名 格式
        boolean revivalOk = grantNotificationListener(c, pkg, revivalComp, sdk);
        if (revivalOk) {
            GuardLog.i(this, tag + "已代开通知使用权（复活监听 "
                    + revivalComp + "）：被清理后系统将自动重绑、秒级拉起");
        } else {
            GuardLog.i(this, tag + "通知使用权未开通（目标 App 可能未内置复活监听服务，"
                    + "或系统版本不支持 ADB 代授权）；不影响原有 10 秒守护拉起");
        }

        // 6) WRITE_SECURE_SETTINGS（三管线自愈命门）：普通签名 App 也能用 ADB 授予。
        //    目标 App 拿到后可把被强停清掉的通知/无障碍配置写回，实现强停秒级复活（对齐 Aerial）。
        boolean wssOk = false;
        String wss = sh(c, "dumpsys package " + pkg + " | grep WRITE_SECURE_SETTINGS");
        if (wss == null || !wss.contains("granted=true")) {
            sh(c, "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            wss = sh(c, "dumpsys package " + pkg + " | grep WRITE_SECURE_SETTINGS");
            wssOk = wss != null && wss.contains("granted=true");
            if (wssOk) GuardLog.i(this, tag + "已代开 WRITE_SECURE_SETTINGS（三管线自愈命门，"
                    + "强停后能自己写回配置、秒级复活）");
        } else {
            wssOk = true;
        }

        // 7) 无障碍服务（第二重系统绑定）：追加开通目标 App 的 KeepAliveAccessibility，保留他人条目。
        String accCls = pkg + ".KeepAliveAccessibility";
        String accComp = pkg + "/" + accCls;
        boolean accOk = grantAccessibility(c, accComp, sdk);
        if (accOk) {
            GuardLog.i(this, tag + "已代开无障碍服务（" + accComp + "）：第二重系统绑定，被清后自动重连");
        }

        String bootState = bootOk ? "已开通" : (dp != null && !bootDeclared ? "App 未声明" : "未开通");
        String wakeState = wakeOk ? "已开通" : (dp != null && !wakeDeclared ? "App 未声明（前台服务自带保活）" : "未开通");
        GuardLog.i(this, tag + "权限自检：悬浮窗 " + (ovOk ? "已开通" : "未开通")
                + " · 电池白名单 " + (wlOk ? "已开通" : "未开通")
                + " · 自启 " + bootState + " · 防休眠 " + wakeState
                + " · 通知使用权 " + (revivalOk ? "已开通" : "未开通")
                + " · 安全设置写入 " + (wssOk ? "已开通" : "未开通")
                + " · 无障碍 " + (accOk ? "已开通" : "未开通"));
    }

    /** 为目标 App 开通无障碍服务（追加保留他人条目），并打开总开关。 */
    private boolean grantAccessibility(AdbClient c, String comp, int sdk) {
        try {
            String cur = sh(c, "settings get secure enabled_accessibility_services");
            String val = (cur == null || cur.trim().isEmpty() || "null".equalsIgnoreCase(cur.trim()))
                    ? comp : (cur.trim().contains(comp) ? cur.trim() : cur.trim() + ":" + comp);
            sh(c, "settings put secure enabled_accessibility_services " + val);
            sh(c, "settings put secure accessibility_enabled 1");
            String check = sh(c, "settings get secure enabled_accessibility_services");
            return check != null && (check.contains(comp) || check.contains(comp.split("/")[0]));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 通过 ADB 为目标 App 开通通知使用权（复活监听服务），并复核。
     * @param pkg 目标包名
     * @param comp 完整组件名 包名/服务类名
     * @param sdk 设备 API 等级
     * @return 是否已开通
     */
    private boolean grantNotificationListener(AdbClient c, String pkg, String comp, int sdk) {
        try {
            if (sdk >= 29) {
                // Android 10+：cmd notification allow_listener
                String out = sh(c, "cmd notification allow_listener " + comp);
                if (out != null && out.toLowerCase().contains("unknown")) {
                    // 极少数系统无该 cmd，回退到 secure setting
                    sh(c, "settings put secure enabled_notification_listeners " + comp);
                }
            } else {
                // Android 9-：追加到 secure setting，保留原有值（冒号分隔）
                String cur = sh(c, "settings get secure enabled_notification_listeners");
                String val = (cur == null || cur.trim().isEmpty() || "null".equalsIgnoreCase(cur.trim()))
                        ? comp : (cur.trim().contains(comp) ? cur.trim() : cur.trim() + ":" + comp);
                sh(c, "settings put secure enabled_notification_listeners " + val);
            }
            // 复核：不同系统回显格式不同，只要出现组件名或目标包名即认为成功
            String check = sh(c, "settings get secure enabled_notification_listeners");
            return check != null && (check.contains(comp) || check.contains(pkg));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- 维护：安装 / 授权
    /**
     * 安装 / 授权是手动一次性操作，需要先让守护循环让出 ADB 连接：
     * 临时把守护循环停下来，等它退出当前轮，再在独立线程里执行，完成后若原本在守护则重启。
     */
    private void runMaintenance(final String action, final String taskId) {
        if (!GuardState.adbOk) {
            GuardLog.i(this, "ADB 未连接，已取消"
                    + (ACTION_INSTALL.equals(action) ? "安装" : "授权") + "：请先点「连接 ADB」");
            GuardState.set("ADB 未连接", "请先点击「连接 ADB」，成功后再执行安装 / 授权");
            updateNoti("等待连接 ADB");
            return;
        }
        final boolean wasRunning = (worker != null && worker.isAlive());
        maintenance = true;
        alive = false;                       // 让循环线程跑完手上的连接后退出
        if (worker != null) {
            worker.interrupt();
            try { worker.join(20000); } catch (InterruptedException e) {}
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    GuardTask one = taskById(taskId);
                    GuardLog.i(GuardService.this,
                        (ACTION_INSTALL.equals(action) ? "开始安装任务 App："
                                                      : "开始开通任务 App 权限：")
                        + (one == null ? "全部已启用任务" : one.label));
                    if (ACTION_INSTALL.equals(action)) installAll(taskId);
                    else grantAll(taskId);
                } catch (Throwable t) {
                    GuardLog.i(GuardService.this, "维护任务异常：" + t);
                } finally {
                    maintenance = false;
                    // v2.9：原「单次检测」按钮已取消 —— 安装完成后自动跑一次完整复核
                    if (ACTION_INSTALL.equals(action) && GuardState.adbOk && !disconnected) {
                        GuardLog.i(GuardService.this, "安装后自动检测一次（服务状态 / 权限 / 拉起 / 复核）");
                        try { cycle(); }
                        catch (Throwable t) { GuardLog.i(GuardService.this, "安装后检测异常：" + t); }
                    }
                    if (wasRunning && !disconnected) { alive = true; startWorker(false); }
                    GuardLog.i(GuardService.this, "维护任务结束");
                }
            }
        }, "xsd-guard-maint").start();
    }

    /**
     * v3.1：查询所有启用设备上安装的第三方应用包名。
     * 与安装 / 授权一样是先让守护循环让出 ADB 连接，再在独立线程里逐台查询，
     * 结果合并去重排序后写入 GuardState 供界面展示与复制。
     */
    private void runListPackages() {
        if (!GuardState.adbOk) {
            GuardLog.i(this, "查询包名前请先点「连接 ADB」，成功后即可查询");
            GuardState.set("ADB 未连接", "请先点击「连接 ADB」，成功后再查询电视应用包名");
            updateNoti("等待连接 ADB");
            return;
        }
        final boolean wasRunning = (worker != null && worker.isAlive());
        maintenance = true;
        alive = false;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(20000); } catch (InterruptedException e) {}
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    queryPackages();
                } catch (Throwable t) {
                    GuardLog.i(GuardService.this, "查询包名异常：" + t);
                    GuardState.pkgQueryHint = "查询失败：" + t;
                } finally {
                    maintenance = false;
                    if (wasRunning && !disconnected) { alive = true; startWorker(false); }
                    GuardState.fire();
                    GuardLog.i(GuardService.this, "查询包名结束");
                }
            }
        }, "xsd-guard-pkgs").start();
    }

    /** 逐台设备执行 pm list packages -3，合并所有第三方包名。 */
    private void queryPackages() {
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        int on = countOn(devs);
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) {
            GuardLog.i(this, "查询包名失败：本机 ADB 密钥不可用，请先点「重置 ADB 密钥」");
            GuardState.pkgQueryHint = "密钥不可用，请重置 ADB 密钥后重试";
            GuardState.set("查询失败", "本机 ADB 密钥不可用");
            return;
        }
        java.util.TreeSet<String> all = new java.util.TreeSet<String>();
        int okDev = 0;
        for (GuardPrefs.Dev d : devs) {
            if (!d.on) continue;
            String tag = "[" + d.name + "] ";
            String[] addrs = TvCompat.addresses(d.addr);
            if (addrs.length == 0) {
                GuardLog.i(this, tag + "设备地址无效，跳过查询");
                continue;
            }
            boolean got = false;
            for (String addr : addrs) {
                AdbClient c = connectWithRetry(TvCompat.host(addr), TvCompat.port(addr), crypto);
                if (c == null) continue;
                try {
                    String out = c.shell("pm list packages -3");
                    List<String> pkgs = TvCompat.thirdPartyPackages(out);
                    if (!pkgs.isEmpty() || (out != null && out.trim().length() == 0)) {
                        all.addAll(pkgs);
                        got = true;
                        GuardLog.i(this, tag + "查到 " + pkgs.size() + " 个第三方应用包名");
                        break;
                    }
                    // 有输出但解析不到包名：换候选地址重试
                    GuardLog.i(this, tag + "包名输出无法识别：" + oneLine(out));
                } catch (Throwable e) {
                    GuardLog.i(this, tag + "查询中断：" + TvCompat.logLine(e, addr));
                } finally {
                    c.close();
                }
            }
            if (got) okDev++;
        }
        List<String> result = new java.util.ArrayList<String>(all);
        GuardState.thirdPartyPkgs = result;
        if (okDev == 0) {
            GuardState.pkgQueryHint = "没有设备返回包名，请确认已连接且 ADB 授权正常";
            GuardState.set("查询包名失败", GuardState.pkgQueryHint);
            GuardLog.i(this, "查询包名：没能从任何设备读到包名（共 " + on + " 台启用设备）");
        } else {
            GuardState.pkgQueryHint = "共 " + okDev + "/" + on + " 台设备，去重后 " + result.size() + " 个第三方应用";
            GuardState.set("包名已查询", GuardState.pkgQueryHint);
            GuardLog.i(this, "查询包名完成：" + GuardState.pkgQueryHint);
        }
    }

    private void installAll(String taskId) {
        List<GuardTask> ts = selectedTasks(taskId);
        if (ts.isEmpty()) {
            GuardState.set("没有可安装任务", "请先在「守护任务」里添加并启用一个目标 App");
            return;
        }
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) {
            GuardState.set("安装失败", "本机 ADB 密钥不可用，请先点「重置 ADB 密钥」再试");
            return;
        }
        int on = countOn(devs);
        GuardState.set("准备安装任务 App", "共 " + ts.size() + " 个任务 / " + on + " 台设备");
        for (int ti = 0; ti < ts.size(); ti++) {
            GuardTask t = ts.get(ti);
            File cache = new File(getCacheDir(), t.cacheName());
            boolean useCache = false;
            try {
                GuardState.set("正在准备安装包", t.label + " · 来源：" + t.sourceName());
                File prepared = prepareApk(t);
                useCache = true;
                GuardLog.i(this, "安装包就绪：" + t.label + "（" + prepared.length()
                        + " 字节，来源：" + t.sourceName() + "）");
            } catch (Throwable e) {
                // 取包失败但本机有缓存时，仍允许重试（断网 / 本地 URI 暂时失效也能装）
                if (cache.isFile() && cache.length() > 100000) {
                    useCache = true;
                    GuardLog.i(this, "取包失败，改用 " + t.label + " 的缓存包（"
                            + cache.length() + " 字节）：" + TvCompat.logLine(e, t.sourceValue()));
                } else {
                    GuardLog.i(this, t.label + " 取包失败且无缓存：" + TvCompat.logLine(e, t.sourceValue())
                            + "（网址请确认联网；本地 APK 请重新选择一次）");
                    continue;
                }
            }
            int ok = 0;
            for (GuardPrefs.Dev d : devs) {
                if (d.on && installOne(d, crypto, t, cache, useCache)) ok++;
            }
            GuardState.set(ok > 0 ? t.label + "安装完成" : t.label + "安装未完成",
                    ok + "/" + on + " 台成功");
        }
    }

    private boolean installOne(GuardPrefs.Dev d, AdbCrypto crypto, GuardTask t,
                               File apkFile, boolean fromCache) {
        String tag = "[" + d.name + "][" + t.label + "] ";
        String[] addrs = TvCompat.addresses(d.addr);
        if (addrs.length == 0) {
            GuardLog.i(this, tag + "设备地址无效，请填 IP:端口");
            return false;
        }
        Throwable last = null;
        for (String addr : addrs) {
            AdbClient c = connectWithRetry(TvCompat.host(addr), TvCompat.port(addr), crypto);
            if (c == null) { last = new IOException("连接失败"); continue; }
            try {
                GuardLog.i(this, tag + "已连接 " + addr + "，推送 APK");
                GuardState.set("正在推送 APK", t.label + " · " + d.name);
                InputStream apk = openApk(apkFile);
                c.push(apk, t.remotePath());
                apk.close();
                GuardLog.i(this, tag + "APK 已推送，执行安装");
                GuardState.set("正在安装 " + t.label, d.name);
                c.setReadTimeout(90000);   // pm install 可能超过默认 30 秒才回输出
                String out = c.shell(TvCompat.installCommand(t.remotePath()));
                c.setReadTimeout(30000);
                android.util.Log.i("XsdGuard", tag + "install out: " + oneLine(out));
                if (!TvCompat.installFailed(out)) {
                    // 重装会清掉运行时权限：装完立即检测并补齐全部必需权限
                    if (t.autoGrant()) {
                        ensureTvPermissions(c, tag, t);
                        if ("学时兑".equals(t.label)) {
                            GuardLog.i(this, tag + "已自动开通学时兑必需权限（悬浮窗 / 电池白名单 / 自启 / 防休眠）");
                        } else {
                            GuardLog.i(this, tag + "已自动开通 " + t.label
                                    + " 必需权限（悬浮窗 / 电池白名单 / 自启 / 防休眠）");
                        }
                    }
                    if (t.autoLaunch()) {
                        for (String lc : TvCompat.launchCommands(t.pkg)) {
                            try { c.shell(lc); } catch (Exception ignored) {}
                        }
                    }
                    GuardLog.i(this, tag + t.label + "已安装 / 更新"
                            + (t.autoLaunch() ? "并启动" : ""));
                    GuardState.setTaskInstalled(t.id, 1);
                    GuardState.fire();
                    return true;
                }
                GuardLog.i(this, tag + "安装返回异常：" + oneLine(out) + "，换地址重试");
                last = new IOException(oneLine(out));
            } catch (Throwable e) {
                last = e;
                GuardLog.i(this, tag + "安装中断：" + TvCompat.logLine(e, addr));
            } finally {
                c.close();
            }
        }
        GuardState.set("安装失败", t.label + " · " + TvCompat.explain(last, d.addr).hint);
        return false;
    }

    private void grantAll(String taskId) {
        List<GuardTask> ts = selectedTasks(taskId);
        if (ts.isEmpty()) {
            GuardState.set("没有可授权任务", "请先在「守护任务」里添加并启用一个目标 App");
            return;
        }
        List<GuardPrefs.Dev> devs = new GuardPrefs(this).devices();
        AdbCrypto crypto = loadCryptoSafe();
        if (crypto == null) {
            GuardState.set("授权失败", "本机 ADB 密钥不可用，请先点「重置 ADB 密钥」再试");
            return;
        }
        int on = countOn(devs);
        GuardState.set("准备开通权限", "共 " + ts.size() + " 个任务 / " + on + " 台设备");
        for (int ti = 0; ti < ts.size(); ti++) {
            GuardTask t = ts.get(ti);
            int ok = 0;
            for (GuardPrefs.Dev d : devs) {
                if (d.on && grantOne(d, crypto, t)) ok++;
            }
            GuardState.set(ok > 0 ? t.label + "权限已开通" : t.label + "授权未完成",
                    ok + "/" + on + " 台成功");
        }
    }

    private boolean grantOne(GuardPrefs.Dev d, AdbCrypto crypto, GuardTask t) {
        String tag = "[" + d.name + "][" + t.label + "] ";
        String[] addrs = TvCompat.addresses(d.addr);
        if (addrs.length == 0) {
            GuardLog.i(this, tag + "设备地址无效，请填 IP:端口");
            return false;
        }
        Throwable last = null;
        for (String addr : addrs) {
            AdbClient c = connectWithRetry(TvCompat.host(addr), TvCompat.port(addr), crypto);
            if (c == null) { last = new IOException("连接失败"); continue; }
            try {
                int sdk = 0;
                try {
                    sdk = Integer.parseInt(oneLine(c.shell("getprop ro.build.version.sdk")).trim());
                } catch (Exception ignored) {}
                if (sdk > 0) GuardLog.i(this, tag + "设备 Android SDK " + sdk);
                String[] cmds = TvCompat.permissionCommands(t.pkg, sdk);
                for (String cmd : cmds) {
                    String out = null;
                    try { out = c.shell(cmd); }
                    catch (Exception e) { out = "(异常)"; }
                    GuardLog.i(this, tag + cmd + " → " + oneLine(out));
                }
                String chk = null;
                try { chk = c.shell("appops get " + t.pkg + " SYSTEM_ALERT_WINDOW"); }
                catch (Exception ignored) {}
                boolean overlayOk = (chk != null && chk.toLowerCase().contains("allow"));
                GuardLog.i(this, tag + "悬浮窗权限："
                        + (overlayOk ? "已允许" : "未确认（部分 ROM 需到系统设置手动允许）")
                        + (chk != null ? ("（" + oneLine(chk) + "）") : ""));
                // 手动授权也顺手补齐前台服务 / 电池白名单，避免只开一半
                ensureTvPermissions(c, tag, t);
                GuardState.set("权限已开通", t.label + " · " + d.name);
                return true;
            } catch (Throwable e) {
                last = e;
                GuardLog.i(this, tag + "授权中断：" + TvCompat.logLine(e, addr));
            } finally {
                c.close();
            }
        }
        GuardState.set("授权失败", t.label + " · " + TvCompat.explain(last, d.addr).hint);
        return false;
    }

    /** 取 APK：从任务准备好的本机缓存文件读取；两种来源最终都落到这个文件。 */
    private InputStream openApk(File apkFile) throws Exception {
        if (apkFile != null && apkFile.isFile() && apkFile.length() > 100000)
            return new FileInputStream(apkFile);
        throw new IOException("安装包未准备好，请检查下载网址或重新选择本地 APK");
    }

    private void downloadApk(File cache, String url) throws Exception {
        if (url == null || url.trim().length() == 0) throw new IOException("下载网址为空");
        GuardLog.i(this, "手机联网从下载网址获取安装包（约 2.6 MB）：" + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url.trim()).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(cache);
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        out.close();
        conn.disconnect();
    }

    /** 连接带重试：守护循环刚让出连接时，盒子可能仍短暂占用，重试几次更稳。 */
    private AdbClient connectWithRetry(String host, int port, AdbCrypto crypto) {
        AdbClient c = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                c = new AdbClient(host, port, crypto);
                c.connect(5000, 20000, 30000);
                return c;
            } catch (Throwable e) {
                if (c != null) { try { c.close(); } catch (Exception ignored) {} c = null; }
                if (attempt < 2) {
                    GuardLog.i(this, "连接 " + host + ":" + port + " 被占用，2.5 秒后重试（"
                            + (attempt + 1) + "/3）");
                    try { Thread.sleep(2500); } catch (InterruptedException ie) { return null; }
                } else {
                    GuardLog.i(this, "连接 " + host + ":" + port + " 失败：" + TvCompat.logLine(e, host + ":" + port));
                }
            }
        }
        return null;
    }

    private AdbCrypto loadCryptoSafe() {
        try { return loadCrypto(); }
        catch (Exception e) {
            GuardLog.i(this, "密钥不可用：" + TvCompat.explain(e, "本机密钥").hint);
            return null;
        }
    }

    private static int countOn(List<GuardPrefs.Dev> devs) {
        int n = 0;
        for (GuardPrefs.Dev d : devs) if (d.on) n++;
        return n;
    }

    private static String oneLine(String s) {
        if (s == null) return "(无输出)";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() > 180 ? t.substring(0, 180) + "..." : t;
    }

    // ------------------------------------------------------------- 密钥
    private AdbCrypto loadCrypto() throws Exception {
        File priv = new File(getFilesDir(), "adb_priv.der");
        File pub = new File(getFilesDir(), "adb_pub.txt");
        if (priv.isFile() && pub.isFile())
            return AdbCrypto.fromParts(readAll(priv), readString(pub));
        if (EmbeddedKey.HAS) {
            AdbCrypto c = AdbCrypto.fromParts(EmbeddedKey.PRIV_DER, EmbeddedKey.PUB_LINE);
            writeAll(priv, EmbeddedKey.PRIV_DER);
            writeString(pub, EmbeddedKey.PUB_LINE);
            GuardLog.i(this, "已导入内置授权密钥（盒子无需再点允许）");
            return c;
        }
        AdbCrypto c = AdbCrypto.generate();
        writeAll(priv, c.privateDer());
        writeString(pub, c.publicLine());
        GuardLog.i(this, "已生成 ADB 密钥：首次连接请在盒子上勾选「始终允许」");
        return c;
    }

    static void resetKey(Context c) {
        try { new File(c.getFilesDir(), "adb_priv.der").delete(); } catch (Throwable ignored) {}
        try { new File(c.getFilesDir(), "adb_pub.txt").delete(); } catch (Throwable ignored) {}
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static String readString(File f) throws IOException {
        return new String(readAll(f), "UTF-8").trim();
    }

    private static void writeAll(File f, byte[] d) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(d); } finally { o.close(); }
    }

    private static void writeString(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    // ------------------------------------------------------------- 前台服务
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Object nm = getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> cls = Class.forName("android.app.NotificationChannel");
            Object ch = cls.getConstructor(String.class, CharSequence.class, int.class)
                           .newInstance(CHANNEL, "学时兑守护", 2);
            nm.getClass().getMethod("createNotificationChannel", cls).invoke(nm, ch);
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000;  // FLAG_IMMUTABLE
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        Notification.Builder b = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            try { b.getClass().getMethod("setChannelId", String.class).invoke(b, CHANNEL); }
            catch (Throwable ignored) {}
        }
        b.setSmallIcon(R.drawable.ic_stat_guard);
        b.setContentTitle("学时兑守护");
        b.setContentText(text);
        b.setOngoing(true);
        b.setContentIntent(pi);
        return b.build();
    }

    private void updateNoti(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, buildNotification(text));
        } catch (Throwable ignored) {}
    }

    private void acquireLocks() {
        // v2.7：取消「防休眠」开关，守护服务运行期间一律保持唤醒 + WiFi 锁
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xsdguard:guard");
            wake.acquire();
        } catch (Throwable t) { GuardLog.i(this, "WakeLock 获取失败：" + t); }
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "xsdguard");
            wifi.acquire();
        } catch (Throwable t) { GuardLog.i(this, "WifiLock 获取失败：" + t); }
    }

    /**
     * v2.11 手动断开 ADB：停守护、停每 10 秒连通检测、锁定安装 / 守护，
     * 并置断开标志阻止一切自动重连；只有再次点「连接 ADB」才解除。
     * App 本身不持有常驻 ADB 通道（每次用完即关），因此断开 = 停止所有主动连接行为。
     */
    private void disconnectAdb() {
        disconnected = true;
        GuardState.disconnected = true;
        alive = false;
        monitorOn = false;
        maintenance = false;
        if (worker != null) worker.interrupt();
        if (monitor != null) monitor.interrupt();
        GuardState.running = false;
        GuardState.adbOk = false;
        GuardState.tvInstalled = 0;          // v2.13：断开后不再显示「已安装可跳过」
        GuardState.set("已手动断开 ADB", "守护与每 " + ADB_RETRY_SEC
                + " 秒连通检测均已停止；需要时点「连接 ADB」重新连接");
        updateNoti("已断开 ADB · 等待手动连接");
        GuardLog.i(this, "已手动断开 ADB：守护与每 " + ADB_RETRY_SEC
                + " 秒连通检测已停止，安装 / 守护已锁定，不会再自动重连；点「连接 ADB」可重新连接");
        stopSelf();
    }

    private void stopAll() {
        alive = false;
        if (worker != null) worker.interrupt();
        GuardState.running = false;
        if (disconnected) {
            // v2.11：手动断开状态下点「停止守护」不改写断开状态、不重启监视
            GuardState.adbOk = false;
            GuardState.set("已手动断开 ADB", "守护已停止，需要时点「连接 ADB」重新连接");
            GuardLog.i(this, "守护已停止（ADB 保持手动断开，不会自动重连）");
            stopSelf();
            return;
        }
        // v2.6：已连上电视时停止守护不销毁服务 —— 连通监视继续，每 10 秒给一次检测结果；
        // 未连接时停止守护直接收尾（没有需要监视的连接）。
        if (GuardState.adbOk) {
            GuardState.set("守护已停止", "连通监视继续：每 " + ADB_RETRY_SEC + " 秒检测一次 ADB 连接");
            updateNoti("守护已停止 · 每 " + ADB_RETRY_SEC + " 秒检测连接");
            GuardLog.i(this, "守护已停止（连通监视继续，每 " + ADB_RETRY_SEC + " 秒检测一次）");
            startMonitor();
            return;
        }
        GuardState.set("已停止", "");
        GuardLog.i(this, "守护已停止");
        stopSelf();
    }

    static void start(Context c, String action) { start(c, action, null); }

    /** v3：指定 taskId 时只对该任务执行安装 / 授权；null 表示全部已启用任务。 */
    static void start(Context c, String action, String taskId) {
        Intent i = new Intent(c, GuardService.class);
        i.setAction(action);
        if (taskId != null) i.putExtra("taskId", taskId);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.lang.reflect.Method m =
                    Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(c, i);
                return;
            } catch (Throwable ignored) {}
        }
        c.startService(i);
    }

    @Override public void onDestroy() {
        alive = false;
        monitorOn = false;
        if (monitor != null) monitor.interrupt();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) {}
        stopForeground(true);
        GuardLog.i(this, "服务销毁");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
