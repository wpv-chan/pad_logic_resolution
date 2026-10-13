# Pad Display

Android 平板原生显示调校 App。直接在本机完成 Android 无线 ADB 配对，通过 `wm size` 和 `wm density` 修改逻辑分辨率与显示密度。无需 root，无需电脑持续连接，无需安装 Shizuku。

当前版本 **1.0.3** 提供「原始分辨率」和「标准 4K」两个常用预设，并保留 ADB 等待结束、自动恢复和错误诊断的修复。旧版可直接覆盖安装以保留配对身份；更新后重新连接本机 ADB。

适用 Android 11 / API 30 及以上，设备需要支持系统无线调试并连接 Wi-Fi。系统无线调试开关必须由用户手动开启，App 提供设置入口。

## 使用

1. 从 [1.0.3 Release](https://github.com/wpv-chan/pad_logic_resolution/releases/tag/v1.0.3) 下载并安装 `PadDisplay-1.0.3-debug.apk`。这是可安装的测试构建，包名 `com.padtools.display`；旧版可以覆盖安装。
2. 在系统设置开启开发者选项，然后开启「无线调试」。
3. 将本应用和系统设置分屏。进入「使用配对码配对设备」，保持这个窗口打开。
4. 在 App 中输入本机 IP、**配对窗口的端口**和六位配对码，点击「配对本机」。mDNS 自动发现成功时，地址和端口会自动填写。
5. 返回无线调试首页，将「IP 地址和端口」中的**连接端口**填写到 App，点击「连接」。两个端口不同；若已发现连接端口，配对成功后会自动尝试连接。
6. 选择「原始分辨率」或「标准 4K（3840×2160）」预设，也可以手动填写宽高与 DPI，点击「应用更改」。在倒计时内选择「保留修改」。屏幕自然方向为竖屏时，4K 预设填写 2160×3840。

再次启动时，会尝试通过发现的本机连接端口重连；也可以手动连接。重启设备、切换网络或重新开启无线调试后，端口可能发生变化。

## 功能

- 原生双列平板界面；支持横竖屏、小窗口、与设置应用分屏。
- 直接进行 ADB TLS / SPAKE2 配对；连接目标限制为本机网络接口和 loopback。
- 读取原始 / 覆盖分辨率与 DPI，显示实际工作区 dp 和最小宽度。
- 宽高原始比例锁定，DPI 滑块和数字精确输入。
- 预设可按屏幕短边比例同步原始 DPI，帮助保留界面元素大小；DPI 保守范围为 120–640。取消「预设同步 DPI」后，选择预设仅更新宽高。
- 标准 4K 为固定的 16:9 分辨率；原始屏幕比例不同时，选择 4K 会取消原始比例锁定，以保留准确的 3840×2160 参数。
- 恢复系统默认，或撤销到修改前已有的自定义参数。
- 设备端 30 秒恢复计时，确认与恢复通过同一锁互斥；恢复不依赖 Activity 的生命周期。
- 保存未完成修改的恢复记录；重连时核对实际恢复结果，必要时重试恢复。
- 应用按钮旁显示执行步骤、进度与错误；命令收到完整退出结果即完成，超时主动中止等待。
- 自动恢复脚本写入和握手在同一命令内完成，收到启动确认后才修改显示参数；后台错误日志不会被丢弃。
- 「操作记录 → 复制诊断」可复制设备型号、Android 版本、失败步骤和原始错误；不包含配对码。
- RSA 配对身份由 Android Keystore 的 AES-GCM 密钥加密保存；不备份、不上传，配对码不写入日志或持久化状态。

`wm` 的宽高以**屏幕自然方向**为准，不跟随当前横竖屏方向旋转。改变 DPI 可能让部分应用切换手机 / 平板布局。这里调整的是系统逻辑显示参数，不是面板的物理像素数量，也不保证所有应用的内部渲染分辨率或性能变化。

## 构建

需要 JDK 17 以上和 Android SDK 36。Gradle Wrapper 已包含；首次构建需要访问 Google Maven、Maven Central 与 JitPack。

```sh
./scripts/build.sh
```

macOS 下脚本会优先使用已安装 Android Studio 的 JBR，以及 `~/Library/Android/sdk`。其他系统设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在 Android Studio 中打开项目。

```sh
./gradlew :app:assembleDebug :adb-lib:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。`scripts/build.sh` 同时将其复制到 `dist/PadDisplay-debug.apk`。用于对外发布时，请配置自己的 release 签名。

## 验证与限制

单元测试检查系统输出解析、参数边界和恢复参数；POSIX shell 集成测试实际执行生成的恢复脚本，验证超时、确认 / 撤销竞争和预设写入。这些脚本测试需要 `sh`。

ADB 通信回归测试使用本地 TCP 对端和实际 ADB 数据包，检查命令 EOF、尾部数据、提前返回的 OPEN 应答、远端拒绝、本地超时中止及 TLS socket 清理。命令结果测试检查分包、无需等待 EOF、退出码和权限错误。这些测试不等同于真实平板上的 TLS 配对或 UI 实测。

恢复脚本可指定 shell 运行测试：`./gradlew :app:testDebugUnitTest -PpadTestShell=/absolute/path/to/mksh`。1.0.2 额外使用从 Android AOSP 源代码编译的 mksh 验证 shell 启动、错误返回和完整应用 / 保留流程，系统 `wm` 在测试中由桩替代。该验证不能替代 Xiaomi Pad 7S Pro / HyperOS 4 上的设备测试。

编译和静态检查可以验证代码与打包，不能替代设备上的 TLS 配对、厂商权限和界面实测。部分厂商系统可能额外限制 shell 修改显示参数；设置入口不支持直接打开无线调试页时会退回开发者选项。

设备端计时脚本需要系统保持运行；设备重启或关机会中断脚本。App 保留未确认修改的恢复记录，可在再次连接后恢复。修改等待确认期间不要卸载 App 或清除数据，否则会丢失这个恢复记录。

如果界面无法操作，也可以使用已授权电脑上的 ADB 手动恢复：

```sh
adb shell wm size reset
adb shell wm density reset
```

## 代码与依赖

- `app/`：原生 Java UI、配对身份、端口发现、ADB 控制和显示事务。
- `adb-lib/`：固定为上游 LibADB Android 3.1.1 源码，增加连接 / TLS 超时与握手失败清理。上游版权声明和许可证保留在目录内。
- `org.conscrypt:conscrypt-android:2.5.3`、Bouncy Castle 1.81、SPAKE2 Android 2.2.1。

技术来源：[Android 无线调试文档](https://developer.android.com/tools/adb)、[AOSP ADB Wi-Fi 架构](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)、[LibADB Android](https://github.com/MuntashirAkon/libadb-android)。
