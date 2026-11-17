package com.xsdanser.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PC 端单测：用假 ROM 输出覆盖 TvCompat 的判活/报错/多地址/拉起兜底。 */
public class TvCompatTest {
    static final String PKG = "com.xsdanser.tv";
    static final String SVC = "dumpsys activity services " + PKG;
    static final String PROC = "dumpsys activity processes";
    static final String MEM = "dumpsys meminfo " + PKG;
    static int pass, fail;

    static class Fake implements TvCompat.Shell {
        final Map<String, String> out = new HashMap<String, String>();
        final List<String> calls = new ArrayList<String>();
        public String run(String cmd) throws Exception {
            calls.add(cmd);
            if (!out.containsKey(cmd)) return "";
            String v = out.get(cmd);
            if (v.startsWith("!THROW")) throw new java.io.IOException("simulated");
            return v;
        }
        Fake put(String cmd, String v) { out.put(cmd, v); return this; }
    }

    static void check(String name, boolean ok, String got) {
        if (ok) pass++; else fail++;
        System.out.printf("%-46s %-8s %s%n", name, ok ? "PASS" : "FAIL", got);
    }

    static void probe(String name, Fake f, Boolean want, String wantVia) {
        TvCompat.Probe p = TvCompat.probe(f, PKG);
        boolean ok = (want == null) ? p.state == null : want.equals(p.state);
        if (wantVia != null) ok = ok && wantVia.equals(p.via);
        check(name, ok, "state=" + p.state + " via=" + p.via + " calls=" + f.calls.size());
    }

    static final String AOSP_HDR = "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n";
    static final String PROC_HDR = "ACTIVITY MANAGER PROCESSES (dumpsys activity processes)\n";
    static final String PS_HDR =
        "USER      PID  PPID  NAME\nroot      1    0     /init\nroot      880  1     zygote64\n";

    public static void main(String[] args) {
        System.out.println("=== 判活五策略（模拟各品牌 ROM 输出）===");
        probe("1 AOSP/小米 services 判活", new Fake().put(SVC,
            AOSP_HDR + "  ServiceRecord{abc " + PKG + "/.QuizOverlayService}\n"),
            Boolean.TRUE, "services");
        probe("2 services 明确判死（nothing）", new Fake().put(SVC,
            AOSP_HDR + "  (nothing)\n"), Boolean.FALSE, "services");
        probe("3 全量服务列表无本包 -> 判死，不翻转", new Fake().put(SVC,
            AOSP_HDR + "  ServiceRecord{x com.ktcp.video/.PlayerService}\n"),
            Boolean.FALSE, "services");
        probe("4 定制 ROM 无 services 头 -> processes 兜底判活", new Fake()
            .put(SVC, "CUSTOM ROM DUMP (no standard header)\n")
            .put(PROC, PROC_HDR + "  Proc # 3: prcp  F/A " + PKG + "/u0a65\n"),
            Boolean.TRUE, "processes");
        probe("5 processes 全量表无本包 -> 判死", new Fake()
            .put(SVC, "UNKNOWN FORMAT\n").put(PROC, PROC_HDR + "  Proc # 0: fore com.ktcp.video\n"),
            Boolean.FALSE, "processes");
        probe("6 recent task 残留包名不算进程", new Fake()
            .put(SVC, "UNKNOWN FORMAT\n")
            .put(PROC, PROC_HDR + "  * Recent #0: " + PKG + "/.MainActivity\n"),
            Boolean.FALSE, "processes");
        probe("7 meminfo 判活", new Fake().put(SVC, "UNKNOWN\n").put(PROC, "UNKNOWN\n")
            .put(MEM, "** MEMINFO in pid 1234 [" + PKG + "] **\n  TOTAL 12345\n"),
            Boolean.TRUE, "meminfo");
        probe("8 meminfo 明确判死", new Fake().put(SVC, "UNKNOWN\n").put(PROC, "UNKNOWN\n")
            .put(MEM, "No process found for: " + PKG + "\n"), Boolean.FALSE, "meminfo");
        probe("9 ps -A 全量表判活（Android 8+）", new Fake().put(SVC, "UNKNOWN\n")
            .put(PROC, "UNKNOWN\n").put(MEM, "UNKNOWN\n")
            .put("ps -A", PS_HDR + "u0_a65  16604 880  " + PKG + "\n"),
            Boolean.TRUE, "ps -A");
        probe("10 ps -A 全量表无本包 -> 判死", new Fake().put(SVC, "UNKNOWN\n")
            .put(PROC, "UNKNOWN\n").put(MEM, "UNKNOWN\n")
            .put("ps -A", PS_HDR + "u0_a12  9999 880 com.ktcp.video\n"),
            Boolean.FALSE, "ps -A");
        probe("11 ps 只列自身 -> 降级到老 ps 判活", new Fake().put(SVC, "UNKNOWN\n")
            .put(PROC, "UNKNOWN\n").put(MEM, "UNKNOWN\n")
            .put("ps -A", "USER PID NAME\nu0_a65 1 sh\n")
            .put("ps", PS_HDR + "u0_a65  16604 880  " + PKG + "\n"),
            Boolean.TRUE, "ps");
        probe("12 全部不可判定 -> 跳过本轮", new Fake().put(SVC, "UNKNOWN\n")
            .put(PROC, "UNKNOWN\n").put(MEM, "UNKNOWN\n")
            .put("ps -A", "USER PID NAME\n").put("ps", "USER PID NAME\n"),
            null, "-");
        probe("13 services 抛异常 -> 继续降级", new Fake().put(SVC, "!THROW")
            .put(PROC, PROC_HDR + "  Proc # 3: " + PKG + "\n"), Boolean.TRUE, "processes");

        System.out.println("\n=== 错误中文分类 ===");
        err("EHOSTUNREACH", new java.net.ConnectException(
            "failed to connect to /192.168.1.100 (port 5555): isConnected failed: EHOSTUNREACH (No route to host)"),
            "设备不在线");
        err("ECONNREFUSED", new java.net.ConnectException("Connection refused"), "ADB 未开启");
        err("SocketTimeout", new java.net.SocketTimeoutException("Read timed out"), "连接超时");
        err("EOF/抢占", new java.io.EOFException(), "连接被抢占");
        err("A_STLS", new java.io.IOException("目标要求 TLS 无线调试配对（Android 11+）"),
            "需要无线调试配对");
        err("UnknownHost", new java.net.UnknownHostException("not.a.host"), "地址无效");
        err("未知异常", new IllegalStateException("weird stuff"), "连接失败");

        System.out.println("\n=== 地址 / 拉起兜底 ===");
        String[] a = TvCompat.addresses("192.168.1.100:5555,192.168.1.100:5557 bad 10.0.0.2");
        check("多地址 + 非法项 + 缺省端口",
              a.length == 3 && a[0].equals("192.168.1.100:5555")
              && a[2].equals("10.0.0.2:5555"), java.util.Arrays.toString(a));
        check("host/port 解析", TvCompat.host("192.168.1.100:5557").equals("192.168.1.100")
              && TvCompat.port("192.168.1.100:5557") == 5557, "ok");
        String[] cmds = TvCompat.launchCommands(PKG);
        check("4 种拉起命令", cmds.length == 4
              && cmds[0].contains("/.MainActivity") && cmds[1].contains(PKG + ".MainActivity")
              && cmds[2].contains("LAUNCHER -p") && cmds[3].startsWith("monkey -p "), "ok");
        check("明确错误才换下一种命令",
              TvCompat.launchFailed("Error type 3\nError: Activity class not found")
              && !TvCompat.launchFailed("Starting: Intent { cmp=... }")
              && !TvCompat.launchFailed("Warning: Activity not started, its current task has been brought to the front"),
              "ok");
        check("pm path 格式兼容", TvCompat.packageInstalled("package:/data/app/x.apk")
              && !TvCompat.packageInstalled("Error: package not found"), "ok");

        System.out.println("\n结果: " + pass + "/" + (pass + fail) + " 通过");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void err(String name, Throwable t, String want) {
        TvCompat.Err e = TvCompat.explain(t, "192.168.1.100:5555");
        check("错误类别 " + name, want.equals(e.status),
              e.status + " | " + e.hint.substring(0, Math.min(46, e.hint.length())) + "...");
    }
}
