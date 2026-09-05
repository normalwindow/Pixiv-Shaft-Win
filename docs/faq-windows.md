# Windows FAQ

Android 问题仍看仓库根目录 [FAQ.md](../FAQ.md)（上游文案）。下面只覆盖桌面端。

### 这是 Google Play 上的 Shaft 吗？

不是。Play 上的 PixShaft / Shaft 是 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft)。本仓是它的 fork，产品是 **Windows 原生客户端**。

### 登录白屏 / 「不正确的请求」

不要用已经登录过的系统 Chrome 配置文件。点应用内登录，让它拉独立 Edge/Chrome（Android UA + QUIC），或用 WebView2 助手。也可以粘贴 `refresh_token`。

### 直连开了但列表 400 / 登得上刷不了

直连必须把 Chromium 拦截器放在鉴权头后面。当前版本已修。若仍失败：关掉 Clash TUN、开应用内「网络测试」，看 Chromium QUIC 和图片无 SNI 哪一步挂。

### 开启直连后无法看图

确认直连开着、系统代理关着。可把图片源切到 pixiv.cat / pixiv.re / pixiv.nl。校园网、部分运营商（移动 / 广电 / 长城）不保证直连。

### 数据在哪？

安装版 `%APPDATA%\PixShaft`。便携 zip 解压后 exe 旁有 `PixShaft.portable`，数据在 `./data`。

### SmartScreen / 杀毒拦截

没有代码签名时 Windows 会警告。选「更多信息 → 仍要运行」。便携包同理。

### 需要 JDK 才能用安装包吗？

不需要。MSI / Exe / 便携 zip 自带 runtime。从源码编译才需要 JDK 17。
