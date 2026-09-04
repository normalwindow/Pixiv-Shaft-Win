# Merge upstream CeuiLiSA/Pixiv-Shaft (classic) into this fork.
# Usage (from repo root):  pwsh -File scripts/sync-upstream.ps1
param(
    [string]$UpstreamRemote = "upstream",
    [string]$UpstreamUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft.git",
    [string]$UpstreamBranch = "classic"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$remotes = git remote
if ($remotes -notcontains $UpstreamRemote) {
    Write-Host "Adding remote $UpstreamRemote -> $UpstreamUrl"
    git remote add $UpstreamRemote $UpstreamUrl
}

Write-Host "Fetching $UpstreamRemote..."
git fetch $UpstreamRemote

Write-Host "Merging $UpstreamRemote/$UpstreamBranch..."
git merge --no-edit "$UpstreamRemote/$UpstreamBranch"
if ($LASTEXITCODE -ne 0) {
    Write-Host @"

Merge conflict. Likely hotspots (see docs/upstream-sync.md):
  app/build.gradle
  app/src/main/java/ceui/lisa/activities/Shaft.java
  app/src/main/java/ceui/lisa/activities/MainActivity.java
  app/src/main/java/ceui/lisa/utils/Settings.java
  app/src/main/java/ceui/pixiv/ui/common/IllustFeedFragment.kt
  app/src/main/java/ceui/lisa/fragments/FragmentSettingsAppearance.java
  app/src/main/res/layout/activity_cover.xml
  app/src/main/res/layout-w840dp/activity_cover.xml
  app/src/main/java/ceui/lisa/view/SpacesItemDecoration.java
  settings.gradle   (append-only: keep :shared / :desktop)
  README.md / README/README.zh-CN.md / README/README.ja.md

Keep AGP / Kotlin / compileSdk from upstream unless a desktop module requires otherwise.
After resolving, restore the fork README overlay:

  Copy-Item docs/readme-win/README.md README.md -Force
  Copy-Item docs/readme-win/README.zh-CN.md README/README.zh-CN.md -Force
  Copy-Item docs/readme-win/README.ja.md README/README.ja.md -Force

"@
    exit 1
}

Write-Host "Merge complete."
Write-Host "If upstream touched README*, restore the overlay from docs/readme-win/ (see docs/upstream-sync.md)."
