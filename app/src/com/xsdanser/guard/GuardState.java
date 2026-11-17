package com.xsdanser.guard;

/** Service 与 Activity 之间的极简共享状态（同进程，无需广播/Binder）。 */
final class GuardState {
    static volatile boolean running;
    /** ADB 是否连通（任一启用设备连接成功即视为连通）。守护只在连通后生效。 */
    static volatile boolean adbOk;
    /** v2.11：是否处于「手动断开 ADB」状态；UI 据此把断开按钮置灰。 */
    static volatile boolean disconnected;
    /** v2.13：电视上是否已安装学时兑：0 未检测 / 1 已安装 / 2 未检测到。已安装时安装步骤可跳过。 */
    static volatile int tvInstalled;
    /** 最近一次 ADB 连通成功的时间戳；为 0 表示从未连通过。 */
    static volatile long lastConnect;
    /** 最近一次周期性连通检测（每约 10 秒一次）的时刻；0 表示监视器还没跑过。 */
    static volatile long lastProbe;
    /** 连通检测累计次数（只统计监视器的周期探测）。 */
    static volatile int probeCount;
    /** 守护轮询已经跑过的轮数。 */
    static volatile int rounds;
    static volatile String status = "未启动";
    static volatile String detail = "";
    static volatile long lastCheck;
    static volatile int checks;
    static volatile int fails;
    static volatile int relaunches;
    /** v2.14：守护期间检测到学时兑被删掉、自动补装的累计次数。 */
    static volatile int reinstalls;
    static volatile String via = "-";
    static volatile Runnable onChange;

    static void set(String s, String d) {
        status = s;
        detail = d == null ? "" : d;
        fire();
    }

    static void setVia(String v) {
        via = (v == null || v.length() == 0) ? "-" : v;
    }

    static boolean isError() {
        return status.equals("设备不在线") || status.equals("ADB 未开启")
            || status.equals("连接超时") || status.equals("连接被抢占")
            || status.equals("连接失败") || status.equals("地址无效")
            || status.equals("手机未联网") || status.equals("需要无线调试配对")
            || status.equals("等待授权") || status.equals("密钥异常")
            || status.equals("包未安装") || status.equals("判活不可判定")
            || status.equals("ADB 未连接") || status.equals("ADB 已断开");
    }

    static void fire() {
        Runnable r = onChange;
        if (r != null) { try { r.run(); } catch (Throwable ignored) {} }
    }

    private GuardState() {}
}
