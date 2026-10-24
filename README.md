# 音声播放器 / Voice Player

**直接安装 / Download APK：**[下载 Android 正式版](https://github.com/asuka030408/YinShengPlayer/releases/latest)。发布的 APK 已内置 SenseVoice、Whisper 和 VAD 离线模型，安装后使用离线识别不需要另外下载模型。

**Ready-to-install Android APK:** [Latest release](https://github.com/asuka030408/YinShengPlayer/releases/latest). The packaged APK includes the SenseVoice, Whisper, and VAD offline models; no separate model download is needed to use offline recognition after installation.

## 中文介绍

音声播放器是一款使用 Android Studio 开发的外语音频播放器。它可以调用在线 API 提前识别音频内容并生成译文，也内置 SenseVoice 和 Whisper 两种离线 ASR 引擎，方便在不同网络环境下使用。

应用会将音频切成多个分段：第一段识别完成后即可开始播放，后续分段则在播放过程中继续识别和翻译，无需等待整段音频处理完毕。通过边听边生成字幕与译文，它让外语音频的听译体验大幅接近体感上的即时翻译，适合听外语歌曲、播客和其他长音频。

### 主要功能

- 从 Android 媒体库或手机文件、文件夹导入本地音频，管理歌单并控制播放。
- 按分段生成原文字幕和译文；已完成的分段保存在本地，可在下次播放时复用。
- 支持全屏滚动字幕，以及应用进入后台时显示的悬浮字幕。
- 可选 SenseVoice、Whisper 离线识别，或腾讯云、阿里云、讯飞在线识别。
- 可配置百度翻译、DeepSeek、智谱或阿里通义进行文本翻译。
- 支持后台批量生成字幕；正式版与 Beta 版使用不同包名，可同时安装。

## English introduction

Voice Player is a foreign-language audio player developed with Android Studio. It can use online APIs to transcribe audio ahead of playback and translate the resulting text. It also includes two offline ASR options, SenseVoice and Whisper, for use in different network conditions.

The app processes audio in segments. Playback can begin as soon as the first segment is ready, while later segments are transcribed and translated in the background. This avoids waiting for the entire recording to be processed. By producing subtitles and translations as you listen, Voice Player offers a near real-time listening experience for foreign-language songs, podcasts, and other long-form audio.

### Features

- Import local audio from the Android media library, individual files, or folders; manage playlists and playback.
- Generate source-language subtitles and translations segment by segment, with local caching of completed segments.
- View scrolling full-screen subtitles or floating subtitles while the app is in the background.
- Choose offline SenseVoice or Whisper ASR, or online ASR from Tencent Cloud, Alibaba Cloud, or iFlytek.
- Configure text translation through Baidu Translate, DeepSeek, Zhipu AI, or Alibaba Tongyi.
- Generate subtitles in the background. The official and Beta apps use separate package names and can coexist.

## 源码与依赖 / Source and dependencies

此仓库保存 Android/Java 源码、资源、测试和[正式版 1.6.0 使用说明书](docs/音声播放器%20正式版%20使用说明书.pdf)。项目使用 Android Gradle Plugin 8.13.0、Java 17，最低支持 Android 7.0（API 24）。

This repository contains the Android/Java source, resources, tests, and the [official v1.6.0 user manual](docs/音声播放器%20正式版%20使用说明书.pdf). It uses Android Gradle Plugin 8.13.0 and Java 17, with a minimum Android API level of 24.

大型第三方二进制文件未纳入源码仓库。构建前需将 `sherpa-onnx-1.13.8.aar` 放到 `app/libs/`；使用离线 ASR 时，还需将 SenseVoice、Whisper 和 Silero VAD 模型放到 `app/src/main/assets/models/`，路径和文件名以 `ModelManager.java` 为准。在线 ASR 与翻译服务需要使用者自行配置 API 凭据。

Large third-party binaries are not included in this source repository. Before building, place `sherpa-onnx-1.13.8.aar` in `app/libs/`. For offline ASR, add the SenseVoice, Whisper, and Silero VAD models under `app/src/main/assets/models/` using the paths and filenames expected by `ModelManager.java`. Configure your own API credentials for online ASR and translation.

本项目的正式版使用说明书描述了最终交付时的功能。自动识别与翻译结果可能有错字、漏字和时间偏差，在线服务也取决于网络、服务状态与配额。

The official manual describes the features delivered with the final release. Automated transcription and translation can contain errors or timing differences; online services also depend on network access, service availability, and quotas.
