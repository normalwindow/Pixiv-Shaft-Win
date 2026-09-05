# 需要替换的图片

README 不再把上游手机截图冒充成本 fork 的界面。下列文件请用 **Windows 窗口实拍** 或专用图标换掉；换完后把路径填进 `docs/readme-win/` 再拷回根 README。

建议分辨率：窗口截图 **1920×1080 或 1600×900**，导出 WebP/PNG；图标 **256×256 PNG** + **.ico（含 16/32/48/256）**。

## 必换（现在仍是 Android / 占位）

| 用途 | 当前文件 | 换成 |
|---|---|---|
| README 横幅 EN | `snap/pixshaft/hero-en.svg`（「装进口袋」手机营销） | `snap/pixshaft/win/hero-en.svg`（已放文字占位，可再换成带窗口截图的版本） |
| README 横幅 ZH | `snap/pixshaft/hero-zh.svg` | `snap/pixshaft/win/hero-zh.svg` |
| README 横幅 JA | 无 | `snap/pixshaft/win/hero-ja.svg` |
| 能力条 | `snap/pixshaft/capabilities-{en,zh}.svg` | Windows 能力条，或删掉改用表格 |
| 技术栈条 | `snap/pixshaft/tech-{en,zh}.svg`（Room/Glide/Cronet） | Compose Desktop / OkHttp / Chromium QUIC |
| CTA 条 | `snap/pixshaft/cta-{en,zh}.svg` | 指向本仓 Releases，文案不要写 Play |
| 窗口图标 | `desktop/icon.ico`、`desktop/src/main/resources/icon.png` | 从 Android `ic_launcher` 临摹来的，需要 Windows 专用标 |
| 应用 Logo | `snap/pixshaft/shaft-logo.png` | 若要和上游品牌区分，换本 fork 的标 |

## README 窗口截图（现在空缺）

放到 `snap/pixshaft/win/`，文件名建议：

| 文件 | 拍什么 |
|---|---|
| `home.webp` | 首页瀑布流 |
| `ranking.webp` | 排行榜 |
| `split.webp` | 左右分栏：左列表右作品 |
| `artwork.webp` | 作品详情 |
| `search.webp` | 搜索 |
| `settings-network.webp` | 设置 → 网络（直连开关） |
| `network-test.webp` | 网络测试页跑完的总览 |
| `login.webp` | 登录页（不要含账号） |

生成脚本 `scripts/build_readme_assets.py` 目前只吃 Pixel 8 真机图（`snap/pixshaft/screens/*.webp`）。Windows 截图不要再套手机外框。

## 可以保留（标明是手机端）

`snap/pixshaft/screens/*.webp` 与 `frames/*.webp` 是上游 Shaft 的 Pixel 8 实拍，只允许出现在 README 的 **「Android（上游同源）」** 小节，标题必须写清「手机端，不是本 Windows 客户端」。

## 不要再用

- Google Play 徽章链到 `ceui.pixiv.pshaft`（那是上游的包名）
- 上游仓库 `CeuiLiSA/Pixiv-Shaft` 里 2020 年的 `snap/ja/*.jpg`（日文 README 曾经外链这些）
