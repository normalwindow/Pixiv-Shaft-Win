# Shaft-Win (サードパーティPixivクライアント)
[![release](https://img.shields.io/github/v/release/normalwindow/Pixiv-Shaft-Win)](https://github.com/normalwindow/Pixiv-Shaft-Win/releases/latest)
[![build status](https://img.shields.io/github/actions/workflow/status/normalwindow/Pixiv-Shaft-Win/gradle.yml?branch=classic)](https://github.com/normalwindow/Pixiv-Shaft-Win/actions)
[![open issues](https://img.shields.io/github/issues/normalwindow/Pixiv-Shaft-Win?color=brightgreen)](https://github.com/normalwindow/Pixiv-Shaft-Win/issues?q=is%3Aopen+is%3Aissue)
[![license](https://img.shields.io/github/license/normalwindow/Pixiv-Shaft-Win)](https://github.com/normalwindow/Pixiv-Shaft-Win/blob/classic/LICENSE)

[English](../README.md) | [简体中文](./README.zh-CN.md) | **日本語**

* このアプリは [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) の `classic` ブランチのフォークです。非公式の Pixiv クライアントに、タブレット / PC 向けワイド画面 UI と、ネイティブ Windows（x64 / ARM64）クライアントのロードマップを加えたものです。
* このプロジェクトはオープンソースであり、当アプリを商業目的で使用することにより発生する責任に関し、一切の責任を負いません。
* アプリ内のすべてのイラスト、漫画、小説の著作権は作者またはPixivに帰属します。
* このアプリは非公式のものです。可能な限り公式のPixivアプリを使用することをお勧めします。
* 上流とのマージ手順は [docs/upstream-sync.md](../docs/upstream-sync.md) を参照してください。

## 1.機能

* ログインと会員登録
* アプリ内VPN（詳しくは[Pix-EzViewer](https://github.com/Notsfsssf/Pix-EzViewer)をご確認ください）
* 非ログインでの人気順表示
* GIFの表示・保存
* 閲覧履歴の表示と保存
* マルチアカウント
* おすすめイラスト・漫画・小説・タグの表示
* イラスト、漫画、小説を人気順・新しい順・古い順で検索
* コメントの閲覧・送信・返信
* 一括ダウンロードとダウンロードリンクの表示
* 小説の閲覧
* Pixivision
* R-18コンテンツの閲覧（Pixiv側での設定が必要）
* スパムコメントのフィルタリング(初期状態では無効)
* ダークモード
* ワイド画面（`sw ≥ 840dp`）ではボトムバーが Navigation Rail になり、列数はペイン幅に合わせて自動（2–8）
* 外付けキーボード：J/K 次/前、F ブックマーク、Ctrl+S ダウンロード、Ctrl+F 検索、Esc、1–5 タブ切替

## 2.特徴

* 様々なクライアントの「いいとこ取り」をした使いやすいUI
* 「人気順」表示
* スムーズなアニメーション
* 操作が簡単
* 小説の閲覧に対応
* 多言語対応

## 3.スクリーンショット


|イラスト表示ページ|おすすめの小説|コメント欄|
|:---:|:---:|:---:|
|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/ja/illust.jpg)|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/QQ20200106-1.jpg)|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/ja/comment.jpg)


|新着作品のタブ|イラスト表示ページ|人気のタグ|
|:---:|:---:|:---:|
|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/QQ20200106-3.jpg)|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/QQ20200106-4.jpg)|![](https://github.com/CeuiLiSA/Pixiv-Shaft/blob/master/snap/ja/hotTag.jpg)

## 4. Google Playからダウンロード

<a href="https://play.google.com/store/apps/details?id=ceui.lisa.pixiv">
    <img
        alt="Get it on Google Play"
        src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png"
        width="330"
        height="128"
    />
</a>

## 5. Githubからダウンロード

[Releases](https://github.com/normalwindow/Pixiv-Shaft-Win/releases/latest)

上流の Android ビルドは [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft/releases/latest) にあります。

ソースからビルド:

```bash
./gradlew assembleGithubDebug
./gradlew :shared:test :desktop:compileKotlin
./gradlew :desktop:run
./gradlew :desktop:packageReleaseDistributionForCurrentOS  # MSI + Exe + ポータブル zip（PixShaft 0.1.0）
./gradlew :desktop:packageReleasePortableZip               # ポータブル zip のみ
```

Windows の MSI / Exe には WiX 3.11 が必要です。GitHub から落とせない場合は `WIX_PATH` を設定してください（[docs/upstream-sync.md](../docs/upstream-sync.md)）。成果物は `desktop/build/compose/binaries/main-release/{exe,msi,zip}/` です。ポータブル zip は `PixShaft.exe` と同じ場所に `PixShaft.portable` を置き、データは `%APPDATA%\PixShaft` ではなく `./data` に保存します。ログインは独立した Chrome/Edge ウィンドウ（Android Chrome UA + QUIC）です。DNS 汚染時、JavaFX 埋め込みページは白い画面のまま止まります。`refresh_token` の貼り付けもできます。

JDK 17+、Android SDK 36。Windows ネイティブクライアントは Compose Multiplatform の `:desktop` です。

## 6. よくある問題とFAQ

[FAQ](./FAQ.md)


## 7. プロジェクトを支援する

Shaft は無料のオープンソースで、余暇に開発・メンテナンスしています。役に立ったと感じたら、
**Afdian（爱发电）** で開発を支援できます。

### 💗 [afdian.com/a/pixshaft](https://afdian.com/a/pixshaft)

支援は完全に任意です。Shaft のすべての機能は今後も無料のままです。
スターを付ける、バグを報告する、プルリクエストを送る — どれも同じくらい助かります。

### ライセンス

Pixiv-Shaft-Win は Pixiv-Shaft のフォークで、[GNU General Public License, version 2](../LICENSE) の下で公開されています。上流: [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft)。

Telegram Android の `SpoilerEffect2` レンダラーとシェーダーは公式 Telegram Android ソースを使用しています。詳細は[第三者通知](../THIRD_PARTY_NOTICES.md)を参照してください。
