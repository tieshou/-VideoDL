# VideoDL

自用的 Android 视频下载 App。基于 [yt-dlp](https://github.com/yt-dlp/yt-dlp)（通过 youtubedl-android），支持 YouTube、X，以及 yt-dlp 支持的其他上千个网站。所有处理都在手机上完成，不需要服务器。

> 仅用于下载你有权保存的内容，请遵守各平台的使用条款。

## 功能

- 粘贴链接下载；也可以在 YouTube、X 等 App 里点「分享」，选 VideoDL，会自动开始下载
- 清晰度：最佳 / 1080p / 720p / 仅音频
  - 「最佳」不设分辨率上限，网站提供多高就下多高（YouTube 可到 4K，可能是 VP9/AV1 编码，老手机播放可能卡）
  - 1080p / 720p 优先选兼容性好的 H.264/MP4
- 有进度条，可以随时取消；下载时保持唤醒、屏幕常亮
- 音视频自动合并成一个文件，按以下顺序尝试：yt-dlp 自带合并 → 调用 ffmpeg → 用 Android 系统的 MediaMuxer
- 保留中文文件名
- 视频保存在 `Movies/VideoDL`，音频保存在 `Music/VideoDL`
- 有「更新引擎」按钮，也会每天自动更新一次 yt-dlp
- 「自检」按钮，以及详细日志，方便排查问题
- 页面底部显示 App 版本和 yt-dlp 版本

## 支持的网站

没有网址白名单，只要 yt-dlp 支持的网站都可以试，比如 B 站、TikTok、微博、小红书、Vimeo、Reddit、Twitch 等。

- Instagram、抖音、Facebook 等通常需要登录 Cookie，目前还不支持导入 Cookie
- Netflix、爱奇艺、腾讯视频等有 DRM 保护或付费的内容不能下载

## 系统要求

- Android 10 及以上（minSdk 29）
- 只支持 arm64-v8a 架构（近几年的手机基本都是）
- APK 大约 75 MB

## 安装

1. 在仓库首页右侧找到 **Releases**，点最新版本
2. 下载 `VideoDL-<版本>.apk`，直接装到手机上。如果手机提示「未知来源」，按提示允许安装就行

（Actions 页面的 Artifacts 里也有一份，是 zip 包，需要先解压。）

每个版本都用同一把签名，新版本可以直接覆盖安装，不会丢设置。

## 自动编译

推送到 `main` 分支后，GitHub Actions 会自动编译（配置在 `.github/workflows/build.yml`），大约 3 分钟。

签名要用到仓库 Secret `KEYSTORE_BASE64`，内容是 keystore 文件做 base64 编码后的结果。keystore 文件**不要**提交到仓库里（已写进 `.gitignore`）。如果换了 keystore，旧版本就没法直接覆盖安装，必须先卸载。

## 技术栈

- Kotlin + Jetpack Compose，主要逻辑在 `app/src/main/java/.../DownloadViewModel.kt`
- `io.github.junkfood02.youtubedl-android:library` / `ffmpeg` 0.18.1
- 包名 `com.handsiron.videodl`
- 发新版本时记得在 `app/build.gradle.kts` 里把 `versionCode` 和 `versionName` 都加一

## 版本记录

- **1.4**：ffmpeg 合并失败时，改用 Android 系统 MediaMuxer 合并
- **1.3**：显示版本号；新增自检按钮和 verbose 日志；保留中文文件名
- **1.2**：用 ffmpeg 自己合并分开的音视频
- **1.1**：改用 v1+v2+v3 签名，修复部分手机装不上的问题
- **1.0**：第一版
