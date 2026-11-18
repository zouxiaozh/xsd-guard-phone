package com.xsdanser.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** 设备列表用「名称|IP:端口|开关」每行一台，避免依赖 org.json / Gson。 */
final class GuardPrefs {
    private static final String FILE = "xsd_guard_prefs";
    static final String DEFAULT_DEVICES = "小米电视|192.168.31.9:5555|1";
    /** v2.0 的旧默认值：覆盖安装且从未改过设备列表时，自动迁移成新默认名。 */
    private static final String LEGACY_DEVICES = "客厅-小米盒子4|192.168.31.9:5555|1";

    private final SharedPreferences sp;

    GuardPrefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String devicesRaw() {
        String v = sp.getString("devices", DEFAULT_DEVICES);
        if (LEGACY_DEVICES.equals(v)) {
            sp.edit().putString("devices", DEFAULT_DEVICES).commit();
            return DEFAULT_DEVICES;
        }
        return v;
    }
    void setDevicesRaw(String s) { sp.edit().putString("devices", s).commit(); }

    int intervalSec() {
        int v = sp.getInt("interval", 10);
        if (v < 3) v = 3;
        if (v > 600) v = 600;
        return v;
    }
    void setIntervalSec(int v) { sp.edit().putInt("interval", v).commit(); }

    /** 学时兑 App 安装包下载网址；安装时先按此地址下载，再经 ADB 推送安装。 */
    static final String DEFAULT_APK_URL =
            "https://xsd.qx47.com/download/XsdAnser-v30-ui.apk";

    String apkUrl() {
        String v = sp.getString("apkurl", DEFAULT_APK_URL);
        if (v == null || v.trim().length() == 0) return DEFAULT_APK_URL;
        return v.trim();
    }
    void setApkUrl(String s) {
        String v = (s == null) ? DEFAULT_APK_URL : s.trim();
        if (v.length() == 0) v = DEFAULT_APK_URL;
        sp.edit().putString("apkurl", v).commit();
    }

    // ------------------------------------------------------------- v3 通用任务配置
    /** 任务串存放键；升级时若没有这个键，就把老的单一下载网址迁移成默认任务。 */
    private static final String TASKS_KEY = "tasks_v3";

    String tasksRaw() {
        return sp.contains(TASKS_KEY) ? sp.getString(TASKS_KEY, "") : "";
    }

    void setTasksRaw(String s) {
        sp.edit().putString(TASKS_KEY, s == null ? "" : s).commit();
    }

    void setTasks(List<GuardTask> list) {
        StringBuilder b = new StringBuilder();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(';');
                b.append(list.get(i).serialize());
            }
        }
        setTasksRaw(b.toString());
    }

    /** 读取任务列表；老版本升级后第一条任务仍是「学时兑」，行为不变。 */
    List<GuardTask> tasks() {
        List<GuardTask> out = new ArrayList<GuardTask>();
        if (!sp.contains(TASKS_KEY)) {
            GuardTask d = GuardTask.defaultTask(apkUrl());
            out.add(d);
            setTasks(out);
            return out;
        }
        String raw = tasksRaw().replace('\r', ';').replace('\n', ';').replace('；', ';');
        String[] lines = raw.split(";");
        for (int i = 0; i < lines.length; i++) {
            GuardTask t = GuardTask.parse(lines[i].trim());
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * 单行输入框下多设备用分号隔开；历史上按换行分隔的配置同样能读，
     * 这样把输入框收成一行也不会丢掉已有的多台设备。
     */
    List<Dev> devices() {
        List<Dev> out = new ArrayList<Dev>();
        String raw = devicesRaw().replace('\r', ';').replace('\n', ';').replace('；', ';');
        String[] lines = raw.split(";");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] p = line.split("\\|");
            if (p.length < 2) continue;
            Dev d = new Dev();
            d.name = p[0].trim();
            d.addr = p[1].trim();
            d.on = p.length < 3 || !p[2].trim().equals("0");
            if (d.addr.lastIndexOf(':') > 0) out.add(d);
        }
        if (out.isEmpty()) {
            Dev d = new Dev();
            d.name = "小米电视";
            d.addr = "192.168.31.9:5555";
            d.on = true;
            out.add(d);
        }
        return out;
    }

    static final class Dev {
        String name;
        String addr;
        boolean on;

        String host() { return addr.substring(0, addr.lastIndexOf(':')); }
        int port() {
            try { return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1)); }
            catch (Exception e) { return 5555; }
        }
    }
}
