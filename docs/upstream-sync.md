# 同步上游 CeuiLiSA/Pixiv-Shaft

本仓库是 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) 的 fork，工作分支只有 `classic`。Windows 桌面端与 Android 宽屏改动都按 **additive / 可合并** 来写，方便反复 `git merge upstream/classic`。

## 日常同步

```powershell
pwsh -File scripts/sync-upstream.ps1
```

脚本会：

1. 若没有 `upstream` remote，则添加 `https://github.com/CeuiLiSA/Pixiv-Shaft.git`
2. `git fetch upstream`
3. `git merge --no-edit upstream/classic`

冲突时按下面的热点文件处理，不要把上游的 AGP / Kotlin / compileSdk 锁死在旧版本。

## 冲突热点

| 文件 | 怎么合 |
|---|---|
| `app/build.gradle` | 保留上游依赖与 AGP 相关改动；桌面端不改这个文件 |
| `settings.gradle` | **只追加** `:shared` / `:desktop`，不要改上游已有 `include` |
| `app/src/main/java/ceui/lisa/activities/Shaft.java` | 保留「首次启动 + sw≥840 才打开平板双栏」那段，插在 `sSettings = Local.getSettings()` 之后、Activity Embedding `install` 之前 |
| `app/src/main/java/ceui/lisa/activities/MainActivity.java` | 保留 rail 菜单同步、`dispatchKeyEvent`、rail 可见时跳过 `BottomBarAutoHide` |
| `app/src/main/java/ceui/lisa/utils/Settings.java` | 保留 `LINE_COUNT_AUTO = 0`；列数解析走 `ShaftColumns`，**不要**发明 `getResolvedLineCount`；**不要**把已有用户的 `lineCount` 默认值改成 0 |
| `app/src/main/java/ceui/pixiv/ui/common/IllustFeedFragment.kt` 及历史/推荐/详情列数调用点 | 列数走 `ShaftColumns.resolved*`，不要写回 `sSettings.lineCount` 当 span |
| `app/src/main/java/ceui/lisa/fragments/FragmentSettingsAppearance.java` | 列数选项含「自动」；保留键盘快捷键说明行 |
| `app/src/main/res/layout/activity_cover.xml` | 默认 layout **必须**带 `navigation_rail`（`visibility=gone`），否则 `ActivityCoverBinding` 会丢字段 |
| `app/src/main/res/layout-w840dp/activity_cover.xml` | fork 新增。上游若改了默认 `activity_cover.xml` 的 id，这里要跟着改，**所有 id 必须与默认 layout 一致** |
| `app/src/main/java/ceui/lisa/view/SpacesItemDecoration.java` | 按 `spanCount` / `spanIndex` 通算，不要再写死 2/3/4 |
| `README.md`、`README/README.zh-CN.md`、`README/README.ja.md` | 合完后用 `docs/readme-win/` 覆盖回 fork 文案 |
| `build.gradle`（根） | 保留上游 AGP/Kotlin；保留 fork 追加的 `org.jetbrains.compose` / `kotlin.plugin.compose`（`apply false`） |
| `settings.gradle` | 保留 `pluginManagement`（给 Compose 插件解析）和文件末尾的 `include ':shared', ':desktop'` |

## README 覆盖

上游 README 只描述官方 Android 应用。fork 的 Windows + Android 文案以 `docs/readme-win/` 为准：

```powershell
Copy-Item docs/readme-win/README.md README.md -Force
Copy-Item docs/readme-win/README.zh-CN.md README/README.zh-CN.md -Force
Copy-Item docs/readme-win/README.ja.md README/README.ja.md -Force
```

徽章指向 `normalwindow/Pixiv-Shaft-Win`，并保留对 CeuiLiSA 原项目的致谢。

合完后如果动过根 `build.gradle` / `settings.gradle`，本地先跑：

```powershell
$env:JAVA_HOME = "C:\Users\kk\.jdks\jdk-17.0.2"
.\gradlew.bat :shared:test :desktop:compileKotlin "-Dorg.gradle.java.installations.paths=C:\Users\kk\.jdks\jdk-17.0.2" "-Dorg.gradle.java.installations.auto-download=false"
```

Windows 安装包（MSI / Exe / 便携 zip）需要 WiX 3.11。Compose 插件会把 zip 缓存到 `%USERPROFILE%\.gradle\compose-jb\wix311.zip`（`onlyIf { !zipFile.isFile }`，坏 zip 要先删）。本机 Gradle/JDK 访问 GitHub 常 SSL 失败；不要用 `Invoke-WebRequest`（会截断）。优先：

```powershell
# 1) NuGet 比 GitHub 稳（nupkg 就是 zip）
$dest = "$env:USERPROFILE\.gradle\compose-jb"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
curl.exe -L --retry 3 --retry-all-errors -o "$dest\wix.3.11.2.nupkg" "https://www.nuget.org/api/v2/package/WiX/3.11.2"
Copy-Item "$dest\wix.3.11.2.nupkg" "$dest\wix.3.11.2.zip" -Force
$tools = "build\wix311\tools"
if (Test-Path "build\wix311") { Remove-Item "build\wix311" -Recurse -Force }
Expand-Archive "$dest\wix.3.11.2.zip" "build\wix311" -Force
$env:WIX_PATH = (Resolve-Path $tools).Path   # 必须含 light.exe / candle.exe
.\gradlew.bat :desktop:packageReleaseDistributionForCurrentOS "-Dorg.gradle.java.installations.paths=C:\Users\kk\.jdks\jdk-17.0.2" "-Dorg.gradle.java.installations.auto-download=false"
```

`packageReleaseDistributionForCurrentOS` 会顺带跑 `packageReleasePortableZip`。便携包在 `desktop/build/compose/binaries/main-release/zip/PixShaft-0.1.0-portable.zip`，解压后 `PixShaft.exe` 旁有 `PixShaft.portable`，数据目录是 `./data`。窗口 / 安装包图标来自 Android `ic_launcher`（`desktop/icon.ico` + `desktop/src/main/resources/icon.png`）。登录不要走系统默认桌面浏览器配置文件：Pixiv `/auth/pixiv/start` 会因 Windows Client Hints 返回「不正确的请求」。桌面端弹出独立 Chrome/Edge（Android UA + QUIC + host-resolver-rules）。本机 DNS 污染时 JavaFX WebView 会一直白屏。

`-Pcompose.desktop.application.downloadWix=false` 在没设 `WIX_PATH` 时会让 `wixToolsetDir` 空值，连 app-image 也会失败。Groovy 访问 `buildTypes.release.proguard.isEnabled` 会踩 Kotlin JavaBean `is*` 命名，不要用它关 ProGuard；release 用 `desktop/proguard-rules.pro` 的 `-dontwarn`。

## 不要做的事

- 不要把 `:app` / `:models` 原地改成 KMP
- 不要在 Windows 首个 exe 上移植 Cronet / ncnn
- 不要给已有用户把 `tabletSplitScreen` 从 false 改成 true（只在首次启动且 `smallestScreenWidthDp >= 840` 时写入）
- 不要用 `widthPixels` 当分栏后的窗格宽度，列数自适应走 pane 宽度 / `screenWidthDp`
