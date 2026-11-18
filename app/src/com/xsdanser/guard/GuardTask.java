package com.xsdanser.guard;

/**
 * v3.0 通用守护任务：一个任务 = 一台/一组电视上的一个目标 App。
 *
 * 安装包来源二选一：
 *   SRC_URL   —— 固定下载网址，运行时联网下载后经 ADB 推送安装；
 *   SRC_LOCAL —— 在守护 App 里点「选择本地 APK」，从手机文件里读取后经 ADB 推送安装。
 *
 * 存储格式（GuardPrefs 里一个字符串，避免依赖 org.json / Gson）：
 *   任务间以 ';' 分隔，字段间以 '|' 分隔，字段内的特殊字符用 %xx 转义。
 */
final class GuardTask {
    /** 预置「学时兑」任务的固定 id，UI 据此禁止删除默认任务。 */
    static final String DEFAULT_ID = "xsd";

    static final int SRC_URL = 0;
    static final int SRC_LOCAL = 1;

    static final int F_INSTALL = 1;   // 被删后自动补装
    static final int F_GRANT = 2;     // 自动补齐权限
    static final int F_LAUNCH = 4;    // 服务掉了自动拉起

    String id;          // 任务唯一标识（也用作本地缓存 / 设备临时文件名）
    String label;       // 显示名称
    String pkg;         // 目标 App 包名
    int source;         // SRC_URL / SRC_LOCAL
    String apkUrl;      // 固定下载网址
    String localUri;    // 手机本地选中的 APK URI（content:// 或 file://）
    String apkName;     // 安装包文件名（仅用于日志展示）
    int flags;          // F_INSTALL / F_GRANT / F_LAUNCH 位组合
    boolean enabled;

    GuardTask() {
        id = "";
        label = "";
        pkg = "";
        source = SRC_URL;
        apkUrl = "";
        localUri = "";
        apkName = "";
        flags = F_INSTALL | F_GRANT | F_LAUNCH;
        enabled = true;
    }

    boolean autoInstall() { return (flags & F_INSTALL) != 0; }
    boolean autoGrant() { return (flags & F_GRANT) != 0; }
    boolean autoLaunch() { return (flags & F_LAUNCH) != 0; }

    /** 网址或本地 URI：按来源取当前有效的安装包地址。 */
    String sourceValue() {
        return source == SRC_LOCAL ? localUri : apkUrl;
    }

    String sourceName() {
        return source == SRC_LOCAL ? "本地 APK" : "固定网址";
    }

    /** 本机缓存名：只用安全字符，避免包名 / URI 里的符号进入文件名。 */
    String cacheName() {
        return "guard_" + safe(id) + ".apk";
    }

    /** 被守护设备上的临时安装包路径。 */
    String remotePath() {
        return "/data/local/tmp/" + cacheName();
    }

    private static String safe(String s) {
        StringBuilder b = new StringBuilder();
        String v = (s == null) ? "task" : s;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        return b.length() == 0 ? "task" : b.toString();
    }

    /** 字段转义：不能让用户输入里的 | ; % 破坏配置串。 */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace("|", "%7C")
                .replace(";", "%3B").replace("\r", "%0D").replace("\n", "%0A");
    }

    static String unesc(String s) {
        if (s == null) return "";
        return s.replace("%0A", "\n").replace("%0D", "\r")
                .replace("%3B", ";").replace("%7C", "|").replace("%25", "%");
    }

    /** 序列化为单行配置；字段顺序固定，便于人工核对与迁移。 */
    String serialize() {
        return esc(id) + "|" + esc(label) + "|" + esc(pkg) + "|" + source + "|"
                + esc(apkUrl) + "|" + esc(localUri) + "|" + esc(apkName) + "|"
                + flags + "|" + (enabled ? "1" : "0");
    }

    /** 解析单行配置；字段数不对或包名为空时返回 null（调用方丢弃坏数据）。 */
    static GuardTask parse(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|", -1);
        if (p.length < 9) return null;
        GuardTask t = new GuardTask();
        try {
            t.id = unesc(p[0]);
            t.label = unesc(p[1]);
            t.pkg = unesc(p[2]);
            t.source = Integer.parseInt(p[3].trim());
            t.apkUrl = unesc(p[4]);
            t.localUri = unesc(p[5]);
            t.apkName = unesc(p[6]);
            t.flags = Integer.parseInt(p[7].trim());
            t.enabled = !"0".equals(p[8].trim());
        } catch (Exception e) {
            return null;
        }
        if (t.id.length() == 0) t.id = "task" + Math.abs(t.pkg.hashCode());
        if (t.label.length() == 0) t.label = t.pkg;
        if (t.source != SRC_LOCAL) t.source = SRC_URL;
        if (t.pkg.length() == 0) return null;
        return t;
    }

    static boolean validPkg(String pkg) {
        if (pkg == null) return false;
        return pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private GuardTask(String id, String label, String pkg, int source, String value,
                      String apkName, int flags, boolean enabled) {
        this.id = id;
        this.label = label;
        this.pkg = pkg;
        this.source = source;
        if (source == SRC_LOCAL) this.localUri = value; else this.apkUrl = value;
        this.apkName = apkName;
        this.flags = flags;
        this.enabled = enabled;
    }

    /** 默认任务：保持老用户升级后行为不变，仍守护学时兑。 */
    static GuardTask defaultTask(String url) {
        String u = (url == null || url.trim().length() == 0)
                ? GuardPrefs.DEFAULT_APK_URL : url.trim();
        return new GuardTask(DEFAULT_ID, "学时兑", "com.xsdanser.tv", SRC_URL, u,
                "XsdAnser-v30-ui.apk", F_INSTALL | F_GRANT | F_LAUNCH, true);
    }
}
