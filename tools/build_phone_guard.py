# -*- coding: utf-8 -*-
"""离线构建「学时兑守护」手机 App（无需 Gradle / Android Studio / 网络）。

复用项目里已有的 sig-tools + toolchain：
  aapt2 link -> ECJ 编译 Java -> d8 出 dex -> 组装 APK -> zipalign -> apksigner 签名

用法（在 adb-tools/phone-guard 目录）:
  python build_phone_guard.py                    # 产出自签名 XsdGuardPhone.apk
  python build_phone_guard.py --embed-adbkey    # 内置本机 ~/.android/adbkey，盒子免授权弹窗
  python build_phone_guard.py --adb-test        # 先跑纯 Java 核心连真实盒子自检
  python build_phone_guard.py --adb-test 192.168.1.100:5555

注意：MiBOX4 的 adbd 同时只允许一个 ADB 传输连接，--adb-test 会临时
kill-server 并在结束后重新 connect，避免与电脑上的守护脚本抢连接。
"""
import argparse
import base64
import glob
import os
import shutil
import subprocess
import sys
import time
import zipfile

PHONE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(PHONE)
T = os.path.join(BASE, "toolchain")
JAVA = os.path.join(BASE, "sig-tools", "jdk-17.0.20.1+1-jre", "bin", "java.exe")
ECJ = os.path.join(T, "ecj.jar")
STUB = os.path.join(T, "android-stub.jar")
D8 = os.path.join(BASE, "sig-tools", "android-14", "lib", "d8.jar")
AAPT2 = os.path.join(BASE, "sig-tools", "android-14", "aapt2.exe")
ZIPALIGN = os.path.join(BASE, "sig-tools", "android-14", "zipalign.exe")
APKSIGNER = os.path.join(BASE, "sig-tools", "android-14", "lib", "apksigner.jar")
ADB = os.path.join(BASE, "platform-tools", "adb.exe")
KS = os.path.join(BASE, "xsdanser-custom.keystore")

SRC = os.path.join(PHONE, "src")
TOOLS = os.path.join(PHONE, "tools")
RES = os.path.join(PHONE, "res")
MANIFEST = os.path.join(PHONE, "AndroidManifest.xml")
ICON_SCRIPT = os.path.join(TOOLS, "make_icons.py")
# 精简自模拟器 framework-res.apk 的 resources.arsc（961 KB），含 android:attr/banner，
# 旧 stub 没有该属性，用它才能在 TV launcher 上显示 banner。
FRAMEWORK = os.path.join(TOOLS, "framework-tv.jar")
OUT_APK = os.path.join(PHONE, "XsdGuardPhone.apk")
PKG = "com.xsdanser.tv"
MIN_API = "21"          # Android 5.0，覆盖绝大多数电视 / 盒子
TARGET_API = "28"


def run(cmd, label, allow_stderr=False):
    try:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=300)
        out = r.stdout.decode("utf-8", "replace")
    except Exception as e:
        print("[FAIL] %s: %s" % (label, e))
        sys.exit(1)
    bad = "error:" in out.lower() or "ERROR in" in out
    if r.returncode != 0 or bad:
        print("[FAIL] %s (rc=%d)\n%s" % (label, r.returncode, out[:4000]))
        sys.exit(1)
    if out.strip() and allow_stderr:
        print("       " + out.strip().replace("\n", "\n       ")[:1200])
    return out


def ensure_icons(force=False):
    """图标资源缺失时自动从正式包提取风格重新生成。"""
    needed = [os.path.join(RES, "drawable-%s" % d, f)
              for d in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
              for f in ("ic_launcher.png", "ic_stat_guard.png")]
    needed.append(os.path.join(RES, "drawable-xhdpi", "tv_banner.png"))
    if not force and all(os.path.isfile(p) for p in needed):
        print("[OK] 图标资源齐全（%d 个）" % len(needed))
        return
    if not os.path.isfile(ICON_SCRIPT):
        print("[FAIL] 缺少图标生成脚本 %s" % ICON_SCRIPT)
        sys.exit(1)
    r = subprocess.run([sys.executable, ICON_SCRIPT],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace")
    print(out.strip()[:1500])
    if r.returncode != 0 or not all(os.path.isfile(p) for p in needed):
        print("[FAIL] 图标生成失败（需要 Pillow 与 XsdAnser-v30-ui.apk）")
        sys.exit(1)


def ensure_assets():
    """（已弃用）历史上把 TV 安装包内置进 assets/，现改为运行期从下载网址获取，不再打包。"""
    return


def check_compat():
    """静态门禁：避免后续改动把兼容层悄悄删掉。"""
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "TvCompat.java"),
              "r", encoding="utf-8") as f:
        tv = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "AdbClient.java"),
              "r", encoding="utf-8") as f:
        ac = f.read()
    with open(MANIFEST, "r", encoding="utf-8") as f:
        mf = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "GuardLog.java"),
              "r", encoding="utf-8") as f:
        lg = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "GuardService.java"),
              "r", encoding="utf-8") as f:
        gs = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "GuardPrefs.java"),
              "r", encoding="utf-8") as f:
        gp = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "MainActivity.java"),
              "r", encoding="utf-8") as f:
        ma = f.read()
    with open(os.path.join(SRC, "com", "xsdanser", "guard", "GuardState.java"),
              "r", encoding="utf-8") as f:
        st = f.read()
    checks = [
        ("minSdk 21", 'minSdkVersion="21"' in mf),
        ("TV launcher", "LEANBACK_LAUNCHER" in mf),
        ("无触屏声明", 'android.hardware.touchscreen' in mf),
        ("自定义图标", 'android:icon="@drawable/ic_launcher"' in mf),
        ("TV banner", 'android:banner="@drawable/tv_banner"' in mf),
        ("五策略判活", all(k in tv for k in
                       ("dumpsys activity services", "dumpsys activity processes",
                        "dumpsys meminfo", '"ps -A"', '"ps"'))),
        ("拉起兜底", all(k in tv for k in ("/.MainActivity", "monkey -p ", "LAUNCHER -p"))),
        ("多地址", "addresses(" in tv and "TvCompat.addresses" in gs),
        ("中文错误", "设备不在线" in tv and "TvCompat.explain" in gs),
        ("日志倒序", "MEM.size() - 1; i >= 0; i--" in lg),
        ("失败计数", "GuardState.fails++" in gs),
        # v2.1 四项 UI 需求，防止后续改动被回退
        ("默认设备", 'DEFAULT_DEVICES = "电视|192.168.1.100:5555|1"' in gp),
        ("配置自动保存", all(k in ma for k in
                       ("addTextChangedListener", "postDelayed(saveTask", "onPause()"))),
        ("设备单行", "setMaxLines(1)" in ma and "setHorizontallyScrolling(true)" in ma),
        ("日志10行折叠", "LOG_PREVIEW = 10" in ma and "folded > 0" in ma),
        # v2.2 安装 / 授权功能门禁，防止后续改动把核心能力悄悄删掉
        ("安装动作", "ACTION_INSTALL" in gs and "installAll" in gs),
        ("授权动作", "ACTION_GRANT" in gs and "grantAll" in gs),
        ("sync 推送", "sync:" in ac and "void push(" in ac),
        ("安装命令", "pm install -r -d" in tv),
        ("权限命令", "SYSTEM_ALERT_WINDOW" in tv and "permissionCommands" in tv),
        ("APK 来源", "APK_URL" in gs and "openApk" in gs),
        # v2.3 ADB 连通门控：连接成功后守护才生效，未连通按短节律自动检测
        ("ADB 连通门控", all(k in gs for k in
                       ("adbOk", "lastConnect", "等待 ADB 连接", "ADB_RETRY_SEC"))),
        ("界面连通状态", "adbOk" in ma and "守护等待 ADB 连接" in ma),
        # v2.4 手动连接：点「连接 ADB」成功才解锁安装 / 授权 / 守护，并周期检查是否仍在连接
        ("手动连接门控", all(k in gs for k in
                       ("ACTION_CONNECT", "runConnect", "connectAll", "monitorLoop", "probeAdb"))),
        ("按钮解锁", "btnConnect" in ma and "btnStart.setEnabled" in ma
                   and "btnInstall.setEnabled" in ma and "btnStart.setText" in ma),
        ("不自动运行", "先连接 ADB" in ma and "打开即" not in ma),
        # v2.5 始终允许检测：首次授权循环等待电视勾选，勾选生效后才解锁
        ("始终允许检测", all(k in gs for k in
                       ("verifyAlwaysAllow", "AUTH_WAIT_ROUNDS", "等待勾选始终允许",
                        "authGrantMillis"))
                   and all(k in ac for k in ("ERR_NOT_TRUSTED", "authDialogShown",
                          "setFailFastAuth", "authGrantMillis"))),
        ("勾选提示", "勾选「始终允许」" in ma),
        # v2.6 权限兜底 + 连通检测心跳：装完/拉起前自动补悬浮窗权限，10 秒检测结果可见
        ("权限兜底自愈", "ensureTvPermissions" in gs and "已自动放行悬浮窗权限" in gs),
        ("安装即授权", "已自动开通学时兑必需权限" in gs),
        ("连通检测心跳", all(k in gs for k in ("lastProbe", "ADB 连通正常"))
                   and "lastProbe" in ma and "最近连通检测" in ma),
        # v2.7 连接结论明确 + 取消开关按钮 + 完整权限集 + 守护前权限自检
        ("连接结论明确", "ADB 连接成功" in gs and "连接任务结束" not in gs),
        ("无开关按钮", "btnAuto" not in ma and "btnAwake" not in ma
                    and "autoStart" not in gp and "keepAwake" not in gp),
        ("完整权限集", all(k in tv for k in
                     ("SYSTEM_ALERT_WINDOW", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK"))),
        ("守护前权限自检", all(k in gs for k in
                     ("ensureAllPermissions", "权限自检：悬浮窗"))),
        # v2.8 主操作按使用顺序连续排列：连接 ADB → 安装学时兑 → 启动守护
        ("主操作顺序", (0 < ma.find("row1.addView(btnConnect")
                    < ma.find("row2.addView(btnInstall")
                    < ma.find("row3.addView(btnStart"))
                   and (0 < ma.find("etApkUrl = new EditText")
                    < ma.find("操作顺序：① 连接 ADB"))),
        # v2.9 三个辅助按钮取消；本机电池白名单 / 判活复核 / 权限开通并入安装与启动守护
        ("精简按钮", all(k not in ma for k in
                   ("立即检测一次", "开通学时兑权限", "保活设置"))),
        ("自动检测并入流程", all(k in gs for k in
                   ("ensurePhoneKeepAlive", "本机保活：已在电池优化白名单",
                    "安装后自动检测一次", "首轮自动执行完整检测"))),
        # v2.10 界面：日志 / 说明放到全部按钮下方，日志字号更小，日志只写结论
        ("日志说明在按钮下", (0 < ma.find("root.addView(row4)")
                    < ma.find("运行日志（最新在最上）")
                    < ma.find("root.addView(btnLogMore)")
                    < ma.find("root.addView(hint)"))),
        ("日志字号更小", "hintSize - (wide ? 4 : 3)" in ma
                    and "hint.setTextSize(hintSize)" in ma
                    and "tvLog.setTextSize(hintSize" in ma
                    and "logTitle.setTextSize(hintSize" in ma),
        ("日志无方式说明", "判活方式" not in gs and "判活方式" not in ma
                    and "拉起方式" not in gs and " · 判活方式 " not in ma),
        # v2.11 手动断开：停守护 / 停 10 秒监视 / 禁止自动重连，必须再次点连接才恢复
        ("断开 ADB 动作", all(k in gs for k in
                   ("ACTION_DISCONNECT", "disconnectAdb", "已手动断开 ADB", "不会再自动重连"))
                   and all(k in ma for k in ("btnDisconnect", "断开 ADB"))),
        ("断开禁自动重连", all(k in gs for k in
                   ("if (disconnected) return;", "monitorOn && !disconnected",
                    "if (disconnected) return false;", "wasRunning && !disconnected"))
                   and "disconnected = false;" in gs and "GuardState.disconnected" in gs),
        # v2.12 统一深色主题：同色系配色 + 圆角卡片 / 按钮 + 按钮按状态刷新
        ("深色和谐主题", all(k in ma for k in
                   ("BG = 0xFF0E1720", "BRAND = 0xFF14B8A6", "GradientDrawable",
                    "styleButton", "setTextColor", "setStatusBarColor"))),
        # v2.13 电视已装学时兑时安装步骤可跳过：连接后自动检测 + 按钮降级提示 + 步骤文字标注
        ("已装可跳过安装", all(k in gs for k in
                   ("probeTvInstalled", "GuardState.tvInstalled", "pm path ",
                    "电视已安装学时兑：可跳过安装"))
                   and all(k in ma for k in
                   ("电视已装可跳过", "学时兑已安装 · 可跳过", "GuardState.tvInstalled"))
                   and "tvInstalled" in st),
        # v2.14 守护中检测学时兑是否被删：被删自动补装 + 唤醒（带冷却，失败不空转）
        ("被删自动补装", all(k in gs for k in
                   ("healReinstall", "HEAL_COOLDOWN_MS", "自动补装完成",
                    "已自动补装并唤醒", "GuardState.reinstalls++"))
                   and all(k in tv for k in ("installState", "pm list packages "))
                   and "reinstalls" in st),
    ]
    bad = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print("  %-14s %s" % (n, "OK" if ok else "MISSING"))
    if bad:
        print("[FAIL] 兼容层门禁未通过：%s" % ", ".join(bad))
        sys.exit(1)
    print("[OK] 兼容层 / 错误提示 / UI 配置门禁 %d/%d 通过"
          % (len(checks), len(checks)))


def read_adbkey():
    home = os.path.expanduser("~")
    priv = os.path.join(home, ".android", "adbkey")
    pub = os.path.join(home, ".android", "adbkey.pub")
    if not (os.path.isfile(priv) and os.path.isfile(pub)):
        print("[FAIL] --embed-adbkey 需要 %s 和 %s" % (priv, pub))
        sys.exit(1)
    with open(priv, "r", encoding="utf-8", errors="replace") as f:
        pem = f.read()
    b64 = "".join(l.strip() for l in pem.splitlines()
                  if l.strip() and not l.strip().startswith("-----"))
    der = base64.b64decode(b64)
    with open(pub, "r", encoding="utf-8", errors="replace") as f:
        line = f.read().strip().split()[0]
    # 与设备已授权的公钥一致性：长度必须为 524 字节（ADB RSAPublicKey 结构）
    if len(base64.b64decode(line + "==")) != 524:
        print("[FAIL] adbkey.pub 不是 524 字节的 ADB 公钥结构")
        sys.exit(1)
    return der, line


def write_embedded_key(path, embed):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not embed:
        body = ("package com.xsdanser.guard;\n"
                "/** 构建期生成：默认不内置私钥，App 首次运行自行生成并请求盒子授权。 */\n"
                "final class EmbeddedKey {\n"
                "    static final boolean HAS = false;\n"
                "    static final byte[] PRIV_DER = new byte[0];\n"
                "    static final String PUB_LINE = \"\";\n"
                "    private EmbeddedKey() {}\n"
                "}\n")
        with open(path, "w", encoding="utf-8") as f:
            f.write(body)
        print("[OK] EmbeddedKey: 不内置密钥（首次连接需盒子点一次「始终允许」）")
        return
    der, line = read_adbkey()
    vals = ",".join("(byte)0x%02x" % b for b in der)
    body = ("package com.xsdanser.guard;\n"
            "/** 构建期生成：内置本机已授权的 adb 密钥，盒子零弹窗。私钥在 APK 内，请自行保管。 */\n"
            "final class EmbeddedKey {\n"
            "    static final boolean HAS = true;\n"
            "    static final byte[] PRIV_DER = new byte[] {%s};\n"
            "    static final String PUB_LINE = \"%s\";\n"
            "    private EmbeddedKey() {}\n"
            "}\n" % (vals, line))
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    print("[OK] EmbeddedKey: 已内置 adbkey（%d 字节 DER）" % len(der))


def tv_selftest():
    """纯 Java 单测：不依赖设备，覆盖各品牌 ROM 的判活 / 报错 / 拉起兜底。"""
    print("=== 兼容层 PC 单测 ===")
    classes = os.path.join(PHONE, "_build", "tvtest")
    os.makedirs(classes, exist_ok=True)
    core = [os.path.join(SRC, "com", "xsdanser", "guard", n + ".java")
            for n in ("B64", "AdbProtocol", "AdbCrypto", "AdbClient", "TvCompat")]
    harness = os.path.join(TOOLS, "TvCompatTest.java")
    run([JAVA, "-cp", ECJ, "org.eclipse.jdt.internal.compiler.batch.Main",
         "-1.8", "-nowarn", "-encoding", "UTF-8", "-d", classes] + core + [harness],
        "ECJ 编译兼容层单测")
    out = run([JAVA, "-cp", classes, "com.xsdanser.guard.TvCompatTest"],
              "运行兼容层单测")
    tail = "\n".join(out.strip().splitlines()[-3:])
    print("       " + tail.replace("\n", "\n       "))
    print("[OK] 各品牌 ROM 判活 / 中文报错 / 多地址 / 拉起兜底单测通过")


def adb_selftest(target):
    print("=== ADB 核心 PC 自检：%s ===" % target)
    classes = os.path.join(PHONE, "_build", "testclasses")
    os.makedirs(classes, exist_ok=True)
    core = [os.path.join(SRC, "com", "xsdanser", "guard", n + ".java")
            for n in ("B64", "AdbProtocol", "AdbCrypto", "AdbClient", "TvCompat")]
    harness = os.path.join(TOOLS, "AdbCliTest.java")
    run([JAVA, "-cp", ECJ, "org.eclipse.jdt.internal.compiler.batch.Main",
         "-1.8", "-nowarn", "-encoding", "UTF-8", "-d", classes] + core + [harness],
        "ECJ 编译核心 + 自检程序")
    run([ADB, "kill-server"], "临时停止 adb server")
    try:
        out = run([JAVA, "-cp", classes, "com.xsdanser.guard.AdbCliTest", target],
                  "连接盒子自检")
        print(out.strip())
        if "PROBE PASS" not in out or "HANDSHAKE OK" not in out:
            print("[FAIL] ADB 自检未通过")
            sys.exit(1)
        print("[OK] ADB 握手 / 鉴权 / shell 全链路通过")
    finally:
        run([ADB, "connect", target], "恢复 adb server 连接")


def main():
    ap = argparse.ArgumentParser(description="构建学时兑守护手机 App")
    ap.add_argument("--embed-adbkey", action="store_true",
                    help="内置本机 ~/.android/adbkey，盒子免授权弹窗（私钥会进 APK）")
    ap.add_argument("--adb-test", nargs="?", const="192.168.1.100:5555",
                    default=None, metavar="HOST:PORT",
                    help="构建前先用纯 Java 核心连盒子自检")
    ap.add_argument("--regen-icons", action="store_true",
                    help="强制按正式包风格重新生成图标 / banner")
    a = ap.parse_args()

    if a.adb_test:
        adb_selftest(a.adb_test)
    tv_selftest()
    check_compat()
    print("[OK] 不内置 TV 安装包：安装时由 App 从「下载网址」联网获取")
    ensure_icons(a.regen_icons)

    stamp = time.strftime("%Y%m%d_%H%M%S")
    build = os.path.join(PHONE, "_build", "run_" + stamp)
    classes = os.path.join(build, "classes")
    dexdir = os.path.join(build, "dex")
    gensrc = os.path.join(build, "gen_src")
    genjava = os.path.join(build, "gen_java")
    flatdir = os.path.join(build, "flat")
    for d in (classes, dexdir, gensrc, genjava, flatdir):
        os.makedirs(d, exist_ok=True)

    for p in (JAVA, ECJ, STUB, D8, AAPT2, ZIPALIGN, APKSIGNER, KS, MANIFEST):
        if not os.path.isfile(p):
            print("[FAIL] 缺少构建依赖：%s" % p)
            return 2

    # 1) 构建期生成 EmbeddedKey.java
    embedded = os.path.join(gensrc, "com", "xsdanser", "guard", "EmbeddedKey.java")
    write_embedded_key(embedded, a.embed_adbkey)

    # 2) aapt2 编译图标 / banner 资源，再链接 manifest（AXML + resources.arsc）
    run([AAPT2, "compile", "--dir", RES, "-o", flatdir], "aapt2 compile res")
    flats = sorted(glob.glob(os.path.join(flatdir, "*.flat")))
    if not flats:
        print("[FAIL] aapt2 未产出 .flat 资源")
        return 2
    base_apk = os.path.join(build, "base.apk")
    fw, manifest = FRAMEWORK, MANIFEST
    if not os.path.isfile(fw):
        # 缺精简 framework 时仍可离线出包，只是 TV 桌面没有 banner
        fw = STUB
        manifest = os.path.join(build, "AndroidManifest.no-banner.xml")
        with open(MANIFEST, "r", encoding="utf-8") as f:
            mf = f.read()
        import re
        mf = re.sub(r'\s*android:banner="[^"]*"', "", mf)
        with open(manifest, "w", encoding="utf-8") as f:
            f.write(mf)
        print("[WARN] 缺 framework-tv.jar，本次构建不含 TV banner")
    link_args = [AAPT2, "link", "-o", base_apk, "-I", fw,
         "--manifest", manifest, "--java", genjava,
         "--min-sdk-version", MIN_API, "--target-sdk-version", TARGET_API]
    link_args += flats
    run(link_args, "aapt2 link")
    print("[OK] AndroidManifest + %d 个图标资源二进制化（framework=%s）"
          % (len(flats), os.path.basename(fw)))

    # 3) ECJ 编译（Java 8 语法，不使用 lambda / java.util.Base64）
    sources = sorted(glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True))
    sources += [embedded]
    sources += sorted(glob.glob(os.path.join(genjava, "**", "*.java"), recursive=True))
    assert not [s for s in sources if s.endswith(".bak")], "备份文件混入源码"
    run([JAVA, "-cp", ECJ, "org.eclipse.jdt.internal.compiler.batch.Main",
         "-1.8", "-nowarn", "-encoding", "UTF-8", "-cp", STUB,
         "-d", classes] + sources, "ECJ 编译 App 源码")
    classfiles = sorted(glob.glob(os.path.join(classes, "**", "*.class"), recursive=True))
    print("[OK] ECJ 编译 %d 个源文件 -> %d 个 class" % (len(sources), len(classfiles)))

    # 4) d8 -> classes.dex
    run([JAVA, "-cp", D8, "com.android.tools.r8.D8", "--min-api", MIN_API,
         "--lib", STUB, "--output", dexdir] + classfiles, "d8 -> classes.dex")
    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.isfile(dex):
        print("[FAIL] d8 未产出 classes.dex")
        return 2
    print("[OK] classes.dex %d bytes" % os.path.getsize(dex))

    # 5) 组装 APK
    unsigned = os.path.join(build, "unsigned.apk")
    zi = zipfile.ZipFile(base_apk, "r")
    zo = zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED)
    for info in zi.infolist():
        zo.writestr(info, zi.read(info.filename))
    zo.write(dex, "classes.dex")
    zo.close()
    zi.close()

    # 6) 对齐 + 同证书签名
    aligned = os.path.join(build, "aligned.apk")
    signed = os.path.join(build, "signed.apk")
    run([ZIPALIGN, "-f", "4", unsigned, aligned], "zipalign")
    run([JAVA, "-jar", APKSIGNER, "sign", "--ks", KS,
         "--ks-pass", "pass:xsdanser123456", "--key-pass", "pass:xsdanser123456",
         "--ks-key-alias", "xsdanser", "--out", signed, aligned], "apksigner sign")
    certs = run([JAVA, "-jar", APKSIGNER, "verify", "--print-certs", signed],
                "apksigner verify")
    badging = run([AAPT2, "dump", "badging", signed], "aapt2 dump badging")
    for line in (certs + "\n" + badging).splitlines():
        if "SHA-256 digest" in line or line.startswith("package:") or "sdkVersion" in line:
            print("       " + line.strip())
    shutil.copyfile(signed, OUT_APK)
    print("\n[OK] 成品：%s (%d bytes)" % (OUT_APK, os.path.getsize(OUT_APK)))
    print("     安装：adb install -r \"%s\"" % OUT_APK)
    return 0


if __name__ == "__main__":
    sys.exit(main())
