# PHASE5.5 调研报告：主题化标题栏 / AI 功能桌面化 / 网页嵌入与 Token 登录

> 状态：**仅供 review，未做任何实现**。批准后按选定方案执行。

---

## 1. 主题化标题栏（把系统标题栏改成符合应用主题的样式）

### 方案 A：DWM 深色标题栏（推荐，低风险）
通过 JNI/JNA 调用 `DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE=20, ...)`，
把系统标题栏切到深色；再配合 `AWTUtilities`/JNA 设置 Windows 11 的圆角
（`DWMWA_WINDOW_CORNER_PREFERENCE=33`）与边框颜色（`DWMWA_BORDER_COLOR=34`，Win11）。

- 优点：保留系统全部行为——Win11 贴靠布局悬浮菜单、拖拽、双击最大化、窗口阴影、
  系统级多任务手势；实现量小（一个 JNI 工具类 + 跟随主题切换）。
- 缺点：标题栏布局仍是系统的（左标题文字、右三键），不能放自定义控件（如搜索框、账号头像）；
  Windows 10 1809 以下不支持深色（回退为浅色）。
- 预计工作量：0.5 天。

### 方案 B：完全自绘标题栏（undecorated）
`Window(undecorated = true)` + Compose 自绘顶栏 + `WindowDraggableArea { }` 实现拖拽，
自绘最小化/最大化/关闭按钮；窗口缩放需在边缘自绘 resize 把手或调用
`User32.SendMessage(WM_NCLBUTTONDOWN, HTBOTTOMRIGHT…)`。

- 优点：视觉 100% 主题化，可放任意控件（全局搜索框、下载进度、头像）。
- 缺点：**丢失 Win11 贴靠布局菜单与原生窗口阴影**（阴影可用 DWM 扩展框架补，
  贴靠键盘快捷键 Win+方向 仍可用）；边缘 resize 手感需要调；跨 DPI 细节多。
- 预计工作量：2–3 天 + 打磨。

### 建议
先做 A（当天可用、零风险），把 B 列为主题设置里的「自绘标题栏（实验性）」开关，并行打磨。

---

## 2. 源项目的 AI 功能在 PC 上是否可以完全落地

源 App 的四个端侧 AI 全部是**调用外部命令行工具**（ncnn 系），
这些工具全都有官方 Windows x64 构建，**无需任何移植即可完全电脑化**：

| 功能 | 工具 | Windows 获取 | 现状 |
|---|---|---|---|
| 超分 | realesrgan-ncnn-vulkan | nihui GitHub Releases（含模型） | 已接（设置页放 exe 即可用） |
| 抠图 | rembg | `pip install rembg`（附 onnx 模型） | 已接 |
| 动图补帧 | rife-ncnn-vulkan | nihui GitHub Releases | 已接（导出动图时可调用） |
| OCR | tesseract | UB-Mannheim Windows 安装包 | 已接 |

桌面端已有 `AiLabScreen`（选图 → 调 exe → 输出）。要"完全对齐手机"还差的是**集成深度**：
1. 工具自动下载器（引导 + 一键下载解压到 `%APPDATA%\PixShaft\tools`）；
2. 下载管线内嵌（下载前/后自动超分，作为下载选项开关）；
3. 动图导出时可选 RIFE 补帧（现只支持手动）。

结论：**可以完全电脑化，且大半已实现**；剩余是把工具获取与调用嵌进下载/动图流程，1–2 天工作量。

---

## 3. 「iframe 式」嵌入网页（以图搜源、账号管理、用户设置页）

三个候选路线：

### 路线 1：KCEF（JCEF 的 Kotlin 打包）——真正的"嵌进 Compose"
在 Compose 窗口内渲染 Chromium（JCEF 基于 AWT Canvas 桥接）。

- 优点：网页成为应用内的一个组件，可以和 Compose 界面完全混排（顶栏是我们的、内容是网页）。
- 缺点：安装包 **+150~250MB**（需捆绑 JCEF 运行时或首次联网下载）；JVM 崩溃风险
  （Chromium 子进程）；维护成本高。
- 适合：如果未来想把「网页版 Pixiv」整个作为应用内的一个页签，值得上。

### 路线 2：扩展现有 WebView2 辅助进程为「浏览窗口」（推荐，轻量）
我们已经自带 `PixShaftWebAuth.exe`（.NET + WebView2）。给它加一个「浏览模式」：
`PixShaftWebAuth.exe --browse <url>` 打开一个独立 WebView2 窗口。

- **关键发现：登录用的 WebView2 profile（chromium-login 目录）会持久化 Cookie——
  用户在应用里登录过一次 Pixiv 后，用同一 profile 打开任何 pixiv.net 页面都是已登录状态**，
  账号管理 / 用户设置页无需再登录。
- 优点：零新增运行时（WebView2 本来就是登录的硬依赖）；实现小（辅助进程加一个参数 + 应用里加菜单项）；
  与登录同引擎，风险低。
- 缺点：是独立系统窗口，不在 Compose 视图层级内（对"设置页/搜图"这类场景完全够用，
  但不是字面意义的 iframe 混排）。
- 预计工作量：0.5–1 天。

### 路线 3：外部浏览器（现状）
以图搜图已经是打开 SauceNAO/ascii2d + 本地图片。保持不变即可作为兜底。

### 各页面对应建议
- **以图搜图**：保留外部打开（SauceNAO/ascii2d 有反爬，嵌入收益低）；
  后续可加「以图搜源」用 Pixiv 官方相近作品接口做站内版。
- **账号管理 / 用户设置页（setting_user.php 等）**：路线 2，打开即已登录。

---

## 4. 用户设置页能否用 Token 登录？

**不能直接用。** 应用持有的是 Pixiv **App API 的 OAuth token**（access/refresh token），
而 `www.pixiv.net` 的网页（含账号设置）认证走的是**网页 Cookie 会话**，两套体系互不通用；
Pixiv 也没有"用 app token 换网页会话"的公开接口。

可行替代（按体验排序）：
1. **WebView2 profile 复用**（见上）——登录过 App 即已登录网页，实际效果等同"免输入"；
2. 嵌入窗口内手动登录一次（Cookie 持久化，之后免登录）；
3. 纯外部浏览器打开（现状兜底）。

---

## 待批准的执行清单（批准后按此做）

| # | 事项 | 方案 | 工作量 |
|---|---|---|---|
| 1 | 主题化标题栏（跟随深浅色） | 方案 A：DWM 深色 + 圆角/边框 | 0.5 天 |
| 2 | （可选）自绘标题栏实验开关 | 方案 B | 2–3 天 |
| 3 | AI 工具引导/自动下载 + 下载管线内嵌超分 + 动图补帧选项 | 扩展现有 AiTools | 1–2 天 |
| 4 | 账号管理 / 用户设置页「应用内打开」 | 方案 2：WebView2 浏览模式（复用登录 profile） | 0.5–1 天 |
| 5 | Token 直接登录网页 | 不可行，走 #4 的 Cookie 方案 | — |
