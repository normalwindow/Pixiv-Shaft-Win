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

## 打包

```powershell
$env:JAVA_HOME = "C:\Users\kk\.jdks\jdk-17.0.2"
$env:WIX_PATH = (Resolve-Path "build\wix311\tools").Path   # 含 light.exe / candle.exe
.\gradlew.bat :desktop:packageReleaseDistributionForCurrentOS `
  "-Dorg.gradle.java.installations.paths=$env:JAVA_HOME" `
  "-Dorg.gradle.java.installations.auto-download=false"
```

产物：

- `desktop/build/compose/binaries/main-release/exe/PixShaft-Win-0.0.3.exe`
- `desktop/build/compose/binaries/main-release/msi/PixShaft-Win-0.0.3.msi`
- `desktop/build/compose/binaries/main-release/zip/PixShaft-Win-0.0.3-portable.zip`
- 登录助手 `PixShaftWebAuth.exe` 与主程序同目录：`.../app/PixShaft-Win/`

WiX 从 GitHub 下失败时用 NuGet，见 [upstream-sync.md](./upstream-sync.md)。窗口 / 安装包图标来自 `desktop/PixShaft.svg`（`desktop/icon.ico` + `desktop/src/main/resources/icon.xml`）。
