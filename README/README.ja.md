<div align="center">

<a href="https://github.com/normalwindow/Pixiv-Shaft-Win">
  <img src="../docs/svg/hero-en.svg" alt="PixShaft-Win — Pixiv を Windows デスクトップへ" width="100%">
</a>

<br>

[![GitHub Release](https://img.shields.io/github/v/release/normalwindow/Pixiv-Shaft-Win?include_prereleases&style=for-the-badge&logo=windows&color=0078d4)](https://github.com/normalwindow/Pixiv-Shaft-Win/releases)
[![GitHub Stars](https://img.shields.io/github/stars/normalwindow/Pixiv-Shaft-Win?style=for-the-badge&logo=github&color=f5c842)](https://github.com/normalwindow/Pixiv-Shaft-Win/stargazers)
[![License](https://img.shields.io/github/license/normalwindow/Pixiv-Shaft-Win?style=for-the-badge&color=blue)](../LICENSE)

<sub>Windows 10+ x64 · Kotlin + Compose Multiplatform · 無料 · オープンソース · 広告なし</sub>

[English](README.en.md) | [简体中文](../README.md) | [日本語](README.ja.md)

</div>

> [!NOTE]
> 非公式のサードパーティ [Pixiv](https://www.pixiv.net) クライアントです。[CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft)（`classic` ブランチ）のフォーク。作品の権利は各作者および Pixiv に帰属します。学習・交流目的のみ。

## これは何

**PixShaft-Win** は、有名なオープンソース Android Pixiv クライアント「Pixiv-Shaft」の **Windows デスクトップ版**です。上流の Android アプリは `classic` ブランチでそのまま維持され、本リポジトリは `desktop/` モジュールとして **Kotlin + Compose Multiplatform** 製のネイティブ Windows クライアントを追加しました。

ログインはシステムの WebView2（Pixeval と同じ方式）。中国本土で**直接接続**可能です。

## 上流（Android 版）との違い

| | 上流 Android | 本デスクトップ |
|---|---|---|
| プラットフォーム | Android 7.0+ | Windows 10+ x64 |
| 技術 | View + XML | Compose Multiplatform |
| サイドバー | ボトムバー | ホバーで展開（コンテンツは動かない） |
| 閲覧 | 単一ペイン | 瀑布流 / 左右ツーペイン |
| ズーム | 固定列数 | **Ctrl+ホイールで無段階ズーム**（40%–300%） |
| ページ状態 | 戻ると再読込 | データ + スクロール位置をキャッシュ |
| ランキング | 19 種 + 日付 | 同じく 19 種 + カレンダー |
| フォロー | すべて/公開/非公開 | イラスト·漫画 / 小説 × すべて/公開/非公開 |
| 検索 | 完全なフィルタ | 履歴 + 発見 + フィルタ（永続化） |

## インストール

[GitHub Releases](https://github.com/normalwindow/Pixiv-Shaft-Win/releases) から `PixShaft-Win-x.y.z.msi/.exe` または `-portable.zip` をダウンロード。初回起動時に WebView2 でログインします。

## ビルド

```bat
gradlew.bat :desktop:packageReleaseDistributionForCurrentOS
```

JDK 17 が必要です。

## スクリーンショット

| | |
|---|---|
| ![home](../docs/screenshots/home.webp) | ![detail](../docs/screenshots/detail.webp) |
| ![ranking](../docs/screenshots/ranking.webp) | ![search](../docs/screenshots/search.webp) |

## License

[GPL-2.0](../LICENSE)（上流より継承）
