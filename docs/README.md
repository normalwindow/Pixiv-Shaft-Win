# 文档索引

本仓库是 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) 的 fork，工作分支只有 `classic`。

**本 fork 的产品是 Windows 原生客户端**（Compose Desktop）。同一棵树里仍能编出 Android APK，并带平板宽屏改动，但 Google Play 上的 Shaft 属于上游，不在这里发。

## 现行

| 文档 | 内容 |
|---|---|
| [desktop.md](./desktop.md) | Windows 架构：登录、直连、数据目录、打包 |
| [direct-connect.md](./direct-connect.md) | 双通道直连：Android Cronet / 桌面 Chromium + 图片无 SNI |
| [image-host.md](./image-host.md) | 图片源切换（官方 / cat / re / nl / 自定义） |
| [faq-windows.md](./faq-windows.md) | Windows 常见问题 |
| [screenshots.md](./screenshots.md) | README 需要替换的图片清单 |
| [upstream-sync.md](./upstream-sync.md) | 如何 merge 上游 `classic` |
| [readme-win/](./readme-win/) | README overlay（合完上游后拷回根目录） |

## Android 模块（上游同源）

| 文档 | 内容 |
|---|---|
| [feeds-module.md](./feeds-module.md) | 自研 Feeds 列表框架 |
| [action-queue.md](./action-queue.md) | 收藏 / 关注持久化队列 |
| [ws-chat-integration.md](./ws-chat-integration.md) | 聊天室 WebSocket |

根目录的 `FAQ.md` / `DOWNLOAD.md` 是上游 Android 文案，merge 时会被覆盖。Windows 请看上面的 `faq-windows.md`。

## 归档

过时计划与工程纪见 [archive/](./archive/)。
