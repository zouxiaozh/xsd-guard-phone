package com.xsdanser.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 电视 / 盒子系统兼容层。纯 Java SE（不引用 android.*），PC 端可用同一份代码做单测。
 *
 * 覆盖差异：
 *  - 判活：AOSP 的 dumpsys activity services、定制 ROM 的 processes、
 *    老 ROM 的 meminfo、以及 4.x/5.x 上只有 ps 能用的情况；
 *  - 拉起：am start 的相对类名 / 全类名、隐式 LAUNCHER、monkey；
 *  - 连接：明文 5555、备用端口、多地址逐个尝试；
 *  - 报错：把 Java 异常翻译成中文可执行的提示。
 */
final class TvCompat {
    static final String SILENT_EXTRA = "com.xsdanser.tv.ZYX_SILENT_LAUNCH";

    /** 可注入的 shell 通道：PC 端单测用假 ROM 输出，App 里用 AdbClient 适配。 */
    interface Shell {
        String run(String cmd) throws Exception;
    }

    /** 判活结果：state 为 null 表示「不可判定」，需要降级到下一策略。 */
    static final class Probe {
        final Boolean state;
        final String via;
        Probe(Boolean s, String v) { state = s; via = v; }
    }

    /** 友好错误：短状态 + 可执行的中文说明，不再把 Java 异常糊到界面上。 */
    static final class Err {
        final String status;
        final String hint;
        Err(String s, String h) { status = s; hint = h; }
    }

    // ------------------------------------------------------------- 错误文案
    static Err explain(Throwable e, String addr) {
        String t = ((e == null ? "" : e.getClass().getName()) + " "
                  + (e == null ? "" : String.valueOf(e.getMessage()))
                  + " " + (e == null ? "" : String.valueOf(e)))
                  .toLowerCase(Locale.US);
        if (t.contains("stls") || t.contains("tls 无线") || t.contains("无线调试配对")) {
            return new Err("需要无线调试配对",
                "这台电视使用 Android 11+ 的无线调试（动态端口），本 App 只支持固定端口的明文 ADB。"
              + "请在电视「开发者选项」里打开「网络 ADB 调试 / 5555」，或用电脑执行一次 adb tcpip 5555。");
        }
        if (t.contains("ehostunreach") || t.contains("no route to host")
                || t.contains("enetunreach")) {
            return new Err("设备不在线",
                "电视可能已关机、IP 变了，或手机与电视不在同一局域网（" + addr + "）。"
              + "建议给盒子绑定固定 IP。");
        }
        if (t.contains("econnrefused") || t.contains("connection refused")) {
            return new Err("ADB 未开启",
                "电视拒绝了 ADB 连接：网络调试被关掉了。请到电视「设置 → 开发者选项」重新打开；"
              + "部分品牌（小米 / 酷开 / TCL）断电重启会自动关闭，需要手动开一次。");
        }
        if (t.contains("unknownhost") || t.contains("not associated") || t.contains("unresolved")) {
            return new Err("地址无效",
                "设备列表里的地址填错了（" + addr + "），请改成 IP:端口，例如 192.168.31.9:5555。");
        }
        if (t.contains("enonet") || t.contains("network is unreachable")
                || t.contains("network unavailable")) {
            return new Err("手机未联网",
                "请打开手机 WiFi，并确认与电视连的是同一个局域网。");
        }
        if (t.contains("sockettimeout") || t.contains("timed out") || t.contains("timeout")
                || t.contains("etimedout") || t.contains("read timed out")) {
            return new Err("连接超时",
                "网络不通，或电视的唯一 ADB 连接正被电脑占用——盒子同一时间只允许一个 ADB 客户端，"
              + "请先停掉电脑上的守护脚本 / adb server。");
        }
        if (t.contains("eof") || t.contains("closed") || t.contains("connection reset")
                || t.contains("broken pipe") || t.contains("stream closed")) {
            return new Err("连接被抢占",
                "电视把连接断开了，通常是电脑 adb 或另一台手机的守护同时在线。"
              + "请只保留一个守护端。");
        }
        if (t.contains("auth") || t.contains("unauthorized") || t.contains("key")) {
            return new Err("等待授权",
                "请在电视弹出的「允许 USB 调试吗」上勾选「始终允许」；"
              + "若没弹窗，可点本页「重置 ADB 密钥」后重连。");
        }
        String tech = brief(e);
        return new Err("连接失败",
            tech.length() > 0 ? "无法连接 " + addr + "（" + tech + "）。请检查 IP、ADB 开关和网络。"
                              : "无法连接 " + addr + "。请检查 IP、ADB 开关和网络。");
    }

    /** 从异常里榨出一句不涉及 Java 包名的短原因。 */
    private static String brief(Throwable e) {
        if (e == null) return "";
        String m = String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ').trim();
        if (m.length() == 0 || m.equals("null")) m = e.getClass().getSimpleName();
        int cut = m.indexOf(" (");
        if (cut > 8) m = m.substring(0, cut);
        m = m.replace("java.net.", "").replace("java.io.", "")
             .replace("Exception:", "").replace("Exception", "").trim();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    /** 写进 UI 日志的一行：纯中文、无 Java 类名、无英文堆栈。 */
    static String logLine(Throwable e, String addr) {
        Err x = explain(e, addr);
        return x.status + "：" + x.hint;
    }

    /** 只给 logcat 用的技术细节（排查时 adb logcat -s XsdGuard）。 */
    static String tech(Throwable e) {
        if (e == null) return "-";
        return e.getClass().getName() + ": " + brief(e);
    }

    // ------------------------------------------------------------- 地址解析
    /** "IP:端口" 或 "IP:端口,IP:端口"；非法项跳过；缺省端口 5555。 */
    static String[] addresses(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return new String[0];
        String[] parts = raw.split("[,;\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() == 0) continue;
            int c = s.lastIndexOf(':');
            String h = c > 0 ? s.substring(0, c) : s;
            String ps = c > 0 ? s.substring(c + 1) : "5555";
            // 只接受 IPv4 / 域名 / localhost，避免把随手敲错的词当主机名去 DNS
            if (h.indexOf('.') < 0 && !h.equalsIgnoreCase("localhost")) continue;
            try {
                int p = Integer.parseInt(ps);
                if (p < 1 || p > 65535) continue;
                out.add(h + ":" + p);
            } catch (Exception ignored) {}
        }
        return out.toArray(new String[out.size()]);
    }

    static String host(String addr) { return addr.substring(0, addr.lastIndexOf(':')); }
    static int port(String addr) {
        try { return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1)); }
        catch (Exception e) { return 5555; }
    }

    // ------------------------------------------------------------- 判活
    /** 五策略，只有「不可判定」才降级；明确判死不会被后面的策略翻成活的。 */
    static Probe probe(final AdbClient c, String pkg) {
        return probe(new Shell() {
            public String run(String cmd) throws Exception { return c.shell(cmd); }
        }, pkg);
    }

    static Probe probe(Shell sh, String pkg) {
        // 1) 标准：dumpsys activity services <pkg>
        String s = safeShell(sh, "dumpsys activity services " + pkg);
        if (s != null && s.trim().length() > 0) {
            String up = s.toUpperCase(Locale.US);
            boolean knownShape = up.contains("ACTIVITY MANAGER SERVICES")
                    || s.contains("ServiceRecord") || up.contains("(NOTHING)");
            if (knownShape) {
                // 有的 ROM 忽略包名参数、dump 全量服务列表：必须逐行确认
                // ServiceRecord 属于本包，不能只靠「输出里出现过包名」判活。
                if (s.contains("ServiceRecord")) {
                    String[] lines = s.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("ServiceRecord") && lines[i].contains(pkg))
                            return new Probe(Boolean.TRUE, "services");
                    }
                    return new Probe(Boolean.FALSE, "services");
                }
                if (up.contains("ACTIVITY MANAGER SERVICES") || up.contains("(NOTHING)"))
                    return new Probe(Boolean.FALSE, "services");
            }
        }

        // 2) 定制 ROM：dumpsys activity processes（只认进程记录行，避开 recent tasks 残留）
        String p = safeShell(sh, "dumpsys activity processes");
        if (p != null) {
            String pu = p.toUpperCase(Locale.US);
            if (pu.contains("ACTIVITY MANAGER PROCESSES") || pu.contains("PROCESSRECORD")
                    || pu.contains("PROC #")) {
                String[] lines = p.split("\n");
                boolean alive = false;
                for (int i = 0; i < lines.length; i++) {
                    String l = lines[i].trim();
                    if (!l.contains(pkg)) continue;
                    if (l.startsWith("Proc #") || l.startsWith("ProcessRecord{")
                            || l.startsWith("PERS #") || l.startsWith("PID #")
                            || l.contains("ProcessRecord{")) { alive = true; break; }
                }
                return new Probe(Boolean.valueOf(alive), "processes");
            }
        }

        // 3) 老 ROM / 精简 dumpsys：dumpsys meminfo <pkg>
        String m = safeShell(sh, "dumpsys meminfo " + pkg);
        if (m != null && m.trim().length() > 0) {
            String ml = m.toLowerCase(Locale.US);
            if (ml.contains("no process found") || ml.contains("no process")) {
                return new Probe(Boolean.FALSE, "meminfo");
            }
            if (ml.contains("meminfo in pid") || ml.contains("** meminfo")
                    || ml.contains("total")) {
                return new Probe(Boolean.TRUE, "meminfo");
            }
        }

        // 4) Android 8+：ps -A（MiBOX4 的 pidof 会返回全部 PID，绝不能用）
        String psA = safeShell(sh, "ps -A");
        Probe r = fromPs(psA, pkg, "ps -A");
        if (r != null) return r;

        // 5) Android 4.x~7.x：老 ps（无 -A 参数）
        String ps = safeShell(sh, "ps");
        r = fromPs(ps, pkg, "ps");
        if (r != null) return r;

        return new Probe(null, "-");
    }

    /** 只有确认拿到「完整进程表」才敢下结论，避免某些 ROM 只列出自身进程造成误杀。 */
    private static Probe fromPs(String out, String pkg, String via) {
        if (out == null || out.trim().length() == 0) return null;
        String low = out.toLowerCase(Locale.US);
        boolean full = low.contains("zygote") || low.contains("system_server")
                || low.contains("/system/bin") || low.contains("root ");
        if (!full) return null;
        String[] lines = out.split("\n");
        boolean alive = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.length() == 0) continue;
            String[] f = l.split("\\s+");
            String name = f[f.length - 1];
            if (name.equals(pkg) || name.startsWith(pkg + ":")
                    || name.startsWith(pkg + "/")) { alive = true; break; }
        }
        return new Probe(Boolean.valueOf(alive), via);
    }

    private static String safeShell(Shell sh, String cmd) {
        try { return sh.run(cmd); }
        catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------- 拉起
    /** 依次尝试：相对类名 -> 全类名 -> 隐式 LAUNCHER -> monkey。 */
    static String[] launchCommands(String pkg) {
        String ez = " --ez " + SILENT_EXTRA + " true";
        return new String[] {
            "am start -n " + pkg + "/.MainActivity" + ez,
            "am start -n " + pkg + "/" + pkg + ".MainActivity" + ez,
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg,
            "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1",
        };
    }

    /** am start 返回里出现这些才算明确失败；"Warning: Activity not started" 其实是成功。 */
    static boolean launchFailed(String out) {
        if (out == null) return false;
        String l = out.toLowerCase(Locale.US);
        return l.contains("error:") || l.contains("exception") || l.contains("java.lang")
            || l.contains("not found") || l.contains("does not exist")
            || l.contains("permission denial") || l.contains("securityexception")
            || l.contains("unknown command") || l.contains("can't find");
    }

    // ------------------------------------------------------------- 安装 / 授权
    /** 设备端安装命令：-r 保留数据，-d 允许版本回退（重装更稳妥）。 */
    static String installCommand(String remoteApk) {
        return "pm install -r -d " + remoteApk;
    }

    /**
     * 开通学时兑所需权限。关键项是 SYSTEM_ALERT_WINDOW（悬浮窗 / 答题浮层），
     * 用 appops 直接放行；其余为电池白名单、自启（RECEIVE_BOOT_COMPLETED）、
     * 防休眠（WAKE_LOCK）与通知权限（尽力而为，失败不影响主功能）。
     */
    static String[] permissionCommands(String pkg) {
        return permissionCommands(pkg, Integer.MAX_VALUE);
    }

    /** 按设备 Android 版本挑命令：SYSTEM_OVERLAY_WINDOW（API30+）、POST_NOTIFICATIONS（API33+）老系统没有，跳过。 */
    static String[] permissionCommands(String pkg, int sdk) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        l.add("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        if (sdk >= 30) l.add("appops set " + pkg + " SYSTEM_OVERLAY_WINDOW allow");
        l.add("dumpsys deviceidle whitelist +" + pkg);
        l.add("pm grant " + pkg + " android.permission.RECEIVE_BOOT_COMPLETED");
        l.add("pm grant " + pkg + " android.permission.WAKE_LOCK");
        if (sdk >= 33) l.add("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
        return l.toArray(new String[0]);
    }

    /** pm install 失败判定：出现 Failure / INSTALL_FAILED 才算明确失败；空输出（API24+）视为成功。 */
    static boolean installFailed(String out) {
        if (out == null) return false;        // 新版 Android 成功时可能无输出
        String l = out.toLowerCase(Locale.US);
        return l.contains("failure") || l.contains("install_failed")
            || l.contains("error:") || l.contains("exception")
            || l.contains("not enough space") || l.contains("invalid apk")
            || l.contains("parsing error");
    }

    static boolean packageInstalled(String pmOut) {
        return pmOut != null && pmOut.contains("package:");
    }

    /**
     * v3.1：解析 `pm list packages -3` 输出，取出每行 `package:xxx` 的包名，
     * 去重并排序。数据流 / 空输出都返回空表，调用方负责提示。
     */
    static java.util.List<String> thirdPartyPackages(String pmOut) {
        java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        if (pmOut != null) {
            String[] lines = pmOut.split("[\\r\\n]+");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.startsWith("package:")) {
                    String p = s.substring(8).trim();
                    if (p.length() > 0 && p.indexOf(' ') < 0) set.add(p);
                }
            }
        }
        return new java.util.ArrayList<String>(set);
    }

    /**
     * v2.14 安装状态三态判定：1 已安装 / -1 明确未安装 / 0 无法判定。
     * 只用 pm path 判「没装」不可靠（部分 ROM 输出为空或只有一句 Error），
     * 所以再接一次 pm list packages &lt;pkg&gt; 做二次确认；两条命令都拿不到
     * 可识别输出时返回 0，宁可不补装也不误判成被删。
     */
    static int installState(final AdbClient c, final String pkg, String pmOut) {
        if (packageInstalled(pmOut)) return 1;
        Shell sh = new Shell() {
            public String run(String cmd) throws Exception { return c.shell(cmd); }
        };
        String list = safeShell(sh, "pm list packages " + pkg);
        if (list == null) return 0;
        String l = list.toLowerCase(Locale.US);
        if (l.contains("package:" + pkg)) return 1;
        if (l.contains("error") || l.contains("unknown") || l.contains("exception")
                || l.contains("denied")) return 0;
        // 空输出（过滤后没有这个包）或只列出别的包：确认未安装
        return -1;
    }

    private TvCompat() {}
}
