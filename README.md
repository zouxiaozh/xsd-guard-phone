# 学时兑守护 · 手机端

一个装在手机上的配套工具，通过 ADB 管理电视上的「学时兑」App。目的是解决电视端设置繁杂、权限难开的问题——不用开电脑，在手机上点几下就能完成。

## 功能

- **权限一键补齐**：悬浮窗、电池白名单、开机自启、防休眠，开完还会逐项复核
- **远程安装**：把学时兑安装包下到手机，再通过 ADB 推送到电视
- **被删自动装回**：检测到学时兑被卸载，自动下载、安装并唤醒
- **进程保活**：学时兑进程被杀后自动拉起；守护服务被系统清掉后也会自恢复
- **掉线重连**：每 10 秒检测一次 ADB 连接，异常自动恢复，失败按指数退避冷却
- 无需 Root，安卓 5.0+，两个 App 全部免费

## 目录结构

```
app/src/com/xsdanser/guard/   应用源码（11 个 Java 文件）
app/AndroidManifest.xml       清单
app/res/                      图标与 TV 横幅资源
tools/build_phone_guard.py    构建脚本
tools/                        ADB 协议测试与辅助脚本
```

## 构建

```bash
cd tools
python build_phone_guard.py
```

构建脚本用 aapt2 编译资源与清单，ECJ 编译 Java，d8 生成 dex，zipalign 对齐后用 apksigner 签名。

完整构建还需要以下**未随仓库分发**的依赖：

- `sig-tools/`：JDK 17、Android SDK 14 的 aapt2 / d8 / apksigner / zipalign
- `toolchain/`：编译用 stub jar
- `framework-tv.jar`：精简版 framework 资源，用于生成 TV 桌面横幅（缺失时仍可出包，只是没有横幅）
- `xsdanser-custom.keystore`：签名密钥（请用自己的密钥替换，勿提交）

## ADB 实现说明

ADB 协议在 `AdbClient.java` / `AdbProtocol.java` / `AdbCrypto.java` 中从零实现，不依赖第三方库。密钥对在设备首次授权时生成并保存在应用私有目录。

注意：ADB 密钥与设备授权一一绑定。卸载重装守护 App 会生成新密钥，需要在电视上重新授权。

## 说明

- 本仓库不含签名密钥、预编译 APK 与二进制依赖，这些文件不会也无法从仓库中恢复。
- 应用内的 IP 地址输入框需用户自行填写电视的局域网地址。
