# PixShaft Windows

Compose Multiplatform 原生客户端，不是 WSA、不是套 APK、不是 WinUI 重写。模块是 `:desktop` + `:shared`，不把上游 `:app` 改成 KMP。

## 和上游的关系

```
CeuiLiSA/Pixiv-Shaft  classic  --merge-->  本仓 classic
                                            ├ app/ models/ feeds/ …   Android（尽量可贴合上游）
                                            ├ shared/                 JVM 内核（本 fork）
                                            └ desktop/                Compose 窗口（本 fork）
```

合上游：`pwsh -File scripts/sync-upstream.ps1`，热点见 [upstream-sync.md](./upstream-sync.md)。

## 网络

直连默认开：

- **API / OAuth / 网页端点**：无头 Chromium（QUIC + `host-resolver-rules`）。拦截器必须挂在鉴权头**之后**，否则请求没有 `Authorization`。
- **图片**：`i.pximg.net` 走无 SNI TLS + 钉死的 `210.140.139.x`；也可切 pixiv.cat / re / nl / 自定义反代。
- 开关直连、安全 DNS、图片源会立刻重建 OkHttp 客户端并回收无头 Chromium。

细节：[direct-connect.md](./direct-connect.md) · 应用内「网络测试」。

## 登录

优先 WebView2 助手 `PixShaftWebAuth.exe`（和 Pixeval 同类），否则弹出独立 Chrome / Edge：Android Chrome UA + QUIC。不要用系统默认桌面配置文件——Pixiv `/auth/pixiv/start` 会因 Client Hints 返回「不正确的请求」。也可以粘贴 `refresh_token` 或带 `code=` 的回调。

## 数据目录

- 安装版：`%APPDATA%\PixShaft`
- 便携：exe 旁放 `PixShaft.portable` → `./data`

## 窗口（自绘标题栏 / 系统标题栏）

设置 → 外观 → **自绘标题栏** 决定用哪种形态（改完要重启）。宿主是 `window/ChromeWindow.kt`：
普通的**有边框** `JFrame`，正文用 Compose 画。

自绘标题栏走 **JBR 的 `WindowDecorations`**（JetBrains Runtime 原生能力）：

```java
var bar = JBR.getWindowDecorations().createCustomTitleBar();
bar.setHeight(34);
JBR.getWindowDecorations().setCustomTitleBar(frame, bar);
```

它的语义是**把标题栏并进客户区**：窗口顶部那 34px 归应用画，系统只负责这一条上的原生行为
（拖动 / 缩放 / Aero Snap / Win11 贴靠 / 双击最大化 / 右键系统菜单），三大键也是系统画在这一条
的右端（`controls.visible`，明暗由 `controls.dark` 决定），所以**我们不自绘三大键**，只按
`rightInset` 给 Compose 内容留白。因此不需要自己算任何窗口几何，也就不会出现「我们和系统
同时写几何」导致的抖动 / 越拖越小 / 飞出屏幕。

三条容易踩的坑（都实测过）：

- **FlatLaf 的窗口装饰必须关掉。** `flatlaf.useWindowDecorations` / `FlatLaf.setUseNativeWindowDecorations`
  在自绘模式下置 false，否则 FlatLaf 会再插一条自己的标题栏并把内容区往下顶 —— 窗口顶部
  同时出现「FlatLaf 那条 + Compose 那条」，就是双标题栏的成因。
  系统标题栏模式反之：那条要留给 FlatLaf。
- **Compose 的 SkiaLayer 带鼠标监听器，会被判成普通客户区。** JBR 的规则是「没有鼠标监听器
  的组件才算透明」；Compose 那条标题栏里每个鼠标事件都要重新调一次 `forceHitTest(false)`
  （见 `ui/TitleBar.kt` 的 `captionHitTest`），拖动 / 双击最大化才生效。漏调一次就退回 `HTCLIENT`。
- **ComposePanel 自己铺白底。** 标题栏那条要显式画 `colorScheme.surface`，靠外面 JPanel 的底色
  透上来是不行的（会得到一条白条）。右侧让给三大键的那段由 Swing 侧用同一个颜色补上
  （`ChromeWindow.applyTitleBarTheme`）。

另外两件窗口行为也在这个类里：

- **最大化只铺工作区**（`frame.maximizedBounds` 钉到 `Toolkit.getScreenInsets` 算出的工作区），
  任务栏留着；换屏 / 改缩放时重新钉。
- **全屏**（侧栏那个按钮 / 全屏态按 Esc）走 AWT 的 `GraphicsDevice.fullScreenWindow`：
  铺满整块屏幕（含任务栏），退出时还原原来的 bounds 和最大化状态。状态通过
  `ChromeWindow.onFullscreenChanged` 回传给 Compose 侧同步 `WindowState.placement`
  （`Main.kt` 的 `BodyRoot`）。换成 Swing 宿主之后这条链断过一次，别再把 `onToggleFullscreen`
  留成空实现。
  全屏要额外补 `fillClientArea()`：Java 的独占全屏只让系统把窗口铺满，AWT 这边的
  `frame.insets` 还是窗口态的 7px，`RootPaneLayout` 会照着它把内容面板再往里缩一圈 ——
  右边 / 下面因此露出一条窗口底色。顺带把 frame / contentPane / rootPane / 正文面板的底色
  都跟着主题 surface 走（默认是 FlatLaf 的 `#3C3F41`，露出来就是灰条）。
  进出全屏还要 `scheduleBarRefresh()`：JBR 的三大键位置是**挂载那一刻**按窗口尺寸算的，
  窗口尺寸大变之后不会自己重算 —— 按钮会停在旧位置（右侧一大片留白），要 hover 才「跳」回去。
  另外 `fillClientArea()` 直接摆过内容面板的 bounds，此时组件算「已布局」，
  退出全屏光调 `validate()` 不会重排，必须 `invalidate()` 一次，否则窗口缩回去了、内容还是
  全屏那一块（看起来就是「没恢复尺寸」）。
  独占全屏下系统只保留关闭键（最小化 / 最大化在 fullscreen 窗口上没有意义），这是原生行为。

需要 `org.jetbrains.runtime:jbr-api`（**51 KB**，`implementation` 而非 `compileOnly`：
它含 `com.jetbrains.JBR` 桥接类，只放编译期会在启动时报 `NoClassDefFoundError`）。
运行时必须是 JBR；非 JBR 的 JRE 上 `JBR.isWindowDecorationsSupported()` 返回 false，
自动退回系统标题栏（不会崩溃）。

**打包必须带上 JBR**，否则发布包跑在普通 OpenJDK 上，自绘标题栏直接降级成系统标题栏。
Compose 插件的 jlink / jpackage 用的是**跑 Gradle 的那个 JVM 的 `java.home`**（不是项目
toolchain），所以打 JBR 包时把 `JAVA_HOME` 指到 jbrsdk：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbrsdk-17.0.14-windows-x64-b1367.22"
$env:WIX_PATH  = (Resolve-Path "build\wix311\tools").Path
.\gradlew.bat :desktop:packageReleaseDistributionForCurrentOS
```

打完核对 `desktop/build/compose/binaries/main-release/app/PixShaft-Win/runtime/release`
里的 `JAVA_VERSION`（JBR 是 `17.0.14` 这种与 jbrsdk 一致的版本；Temurin 会写成 `17.0.2`）。
换过 JDK 之后要删掉 `desktop/build/compose/tmp/main/runtime` —— 这个任务没有把 JDK 记进
输入，旧镜像会被判成 UP-TO-DATE 直接复用（踩过）。

ProGuard 那边要放行 JBR / FlatLaf / JNA：前者靠反射找实现、后者靠 `MethodHandle`
多态签名调 JDK 内部方法、JNA 全靠反射绑定原生函数。裁掉 JNA 的表现很隐蔽 ——
发布包里 `Native.getWindowPointer` 静默失败，DWM 深色主题不生效，于是「悬停三大键时
图标看不见」这种只在发布包出现的怪现象。见 `desktop/proguard-rules.pro`。

诊断：`%APPDATA%\PixShaft\chrome.log` 启动 / 进出全屏时各写一行（高度 / insets / 窗口样式 /
明暗 / rootPane 与内容面板的 bounds）；命中结果用 `scripts/probe-window.ps1`
（标题栏应为 `HTCAPTION(2)`，最大化键应为 `HTMAXBUTTON(9)`，吸附用 `scripts/capture-window.ps1` 截图核对）。

### 为什么不用另外两条路（都实测失败过，别再走）

- **无边框 + JNA 接管窗口过程**：拖动 / 缩放 / 贴靠全部要自己实现，必然出现两个写入者
  同时改窗口几何 → 抖动、越拖越小、飞出屏幕。
- **FlatLaf 原生窗口边框 + `fullWindowContent`**：判定拖动要遍历控件树，只在**带鼠标监听器**
  的组件上读 `JComponent.titleBarCaption`；Compose 里唯一带监听器的组件是 Skiko 的
  `SkiaLayer$1`，继承自 `java.awt.Canvas`（**不是 `JComponent`**），无法被标记，于是永远判成
  普通内容 → `HTCLIENT` → 拖不动。缩放还能用只是因为缩放热区走了另一条路。

### 体积

同一条 `jlink`、同一组必需模块下的 `lib/modules` 实测：Temurin 17 约 40 MB，
JBR 17 约 51.5 MB（**+11.5 MB**），`jbr-api` 0.05 MB。

## 打包

```powershell
$env:JAVA_HOME = "C:\Users\kk\.jdks\jdk-17.0.2"
$env:WIX_PATH = (Resolve-Path "build\wix311\tools").Path   # 含 light.exe / candle.exe
.\gradlew.bat :desktop:packageReleaseDistributionForCurrentOS `
  "-Dorg.gradle.java.installations.paths=$env:JAVA_HOME" `
  "-Dorg.gradle.java.installations.auto-download=false"
```

产物：

- `desktop/build/compose/binaries/main-release/exe/PixShaft-Win-0.0.4.exe`
- `desktop/build/compose/binaries/main-release/msi/PixShaft-Win-0.0.4.msi`
- `desktop/build/compose/binaries/main-release/zip/PixShaft-Win-0.0.4-portable.zip`
- 登录助手单独产物：`desktop/build/compose/binaries/main-release/webauth/PixShaftWebAuth.exe`（不打进便携包 / MSI，登录页按需下载到数据目录 `bin/`）

WiX 从 GitHub 下失败时用 NuGet，见 [upstream-sync.md](./upstream-sync.md)。窗口 / 安装包图标来自 `desktop/PixShaft.svg`（`desktop/icon.ico` + `desktop/src/main/resources/icon.xml`）。
