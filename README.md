# 影視TV

適用於 Android TV 與手機的影音應用程式，整合媒體瀏覽與播放體驗，並支援外部配置與 [CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) Spider 介面擴充。

**App 本身不內建或提供任何內容來源。** 外部內容需自行配置，也可開啟本地媒體檔案或推送媒體網址。

[使用與開發指南](https://fongmi.github.io/TV/) · [討論群組](https://t.me/fongmi_official)

## 開始使用

1. 安裝適合裝置的 APK：`leanback` 為電視版，`mobile` 為手機版；僅支援 `arm64-v8a`（64 位元 Android 系統），不再提供 ARM32 版本。最低需求為 Android 7.0（API 24）。
2. 在設定中加入自己的配置，格式與欄位見[配置範例](https://fongmi.github.io/TV/config/#examples)。
3. 也可從系統檔案管理員開啟媒體檔案，或透過推送入口播放媒體網址。

## 主要功能

- **播放**：Media3／ExoPlayer、mpv、硬解與 FFmpeg 軟解；字幕、彈幕、音軌、倍速與片頭／片尾跳過。
- **瀏覽與管理**：分類篩選、搜尋、播放記錄、收藏與無痕模式。
- **播放清單**：M3U／TXT／JSON 格式、清單分組與 XMLTV 節目資訊。
- **操作**：電視遙控器、手機手勢、畫中畫與背景音訊。
- **互通**：DLNA 投放／接收、Android Auto、本地 HTTP 控制與裝置同步。

實際能力依配置、媒體、播放引擎與裝置而異；本地 HTTP API 僅供可信任區域網路使用，不要直接轉發到公網。

## 開發文件

| 文件 | 內容 |
| --- | --- |
| [App 功能](https://fongmi.github.io/TV/features/) | 操作與功能介紹 |
| [配置字典](https://fongmi.github.io/TV/config/) | 配置欄位、網路設定與 JSON 範例 |
| [擴充介接](https://fongmi.github.io/TV/spider/) | Java／Python／JavaScript 範例、方法與回傳格式 |
| [本地 API](https://fongmi.github.io/TV/local/) | 播放控制、推送、檔案與同步端點 |
| [網站維護](website/README.md) | 靜態網站本機建置 |

`app/src/main/` 為共用邏輯，`app/src/leanback/`、`app/src/mobile/` 為各自的 UI。模組清單見 [settings.gradle](settings.gradle)，SDK 與依賴版本見 [libs.versions.toml](gradle/libs.versions.toml)。

## Windows 建置

先準備以下環境與檔案：

- **JDK 21、Android SDK、Python 3.12**。SDK 平台版本依 `compileSdk` 設定；Python 可用 `py -3.12 --version` 確認，找不到時在 [chaquo/build.gradle](chaquo/build.gradle) 的 Python 區塊設定 `buildPython`。
- **Media3 原始碼**：在專案根目錄執行 `git clone https://github.com/raptorz/TVBox-media.git TVBox-media`（已有目錄則不必重複 clone）。Gradle 以 composite build 編譯此目錄；`app/libs/lib-*.aar` 會被排除，不再作為播放器依賴。其他協定模組 AAR 仍由 `app/libs/` 載入。
- **原生建置工具**：FFmpeg JNI 由 media 原始碼編譯，需安裝該模組指定的 Android NDK（目前 `29.0.14206865`）及 CMake（`3.21.0` 以上）；media 的 `local.properties` 也需設定 `sdk.dir`。mpv／FFmpeg 等預編譯原生庫由 media 倉庫提供。
- **自己的簽章檔與 `local.properties`**：在儲存庫根目錄建立下列設定，將所有範例值替換成自己的資料。

```properties
sdk.dir=C:/Android/Sdk
storeFile=C:/keys/yingshi-tv.jks
keyAlias=your-key-alias
storePassword=your-keystore-password
keyPassword=your-private-key-password
```

`storePassword` 是 keystore 密碼，`keyPassword` 是 alias 對應的私鑰密碼；未設定 `keyPassword` 時沿用 `storePassword`。不要提交簽章檔或真實密碼。

在儲存庫根目錄以 PowerShell 執行：

```powershell
# 電視版
.\gradlew.bat :app:assembleLeanbackRelease

# 手機版
.\gradlew.bat :app:assembleMobileRelease
```

APK 僅包含 ARM64，輸出至 `Release/apk/TVBox-mobile-arm64_v8a.apk`、`Release/apk/TVBox-tv-arm64_v8a.apk`；App Bundle 也限制為 ARM64。簽章不同的 APK 不能直接覆蓋既有安裝。網站位於 `website/`，可獨立建置，不需編譯 Android App。

## GitHub Actions 簽章建置

網站自動發布工作流已移除。Android 工作流位於 [release.yml](.github/workflows/release.yml)。

在 **Settings → Secrets and variables → Actions** 設定 Repository Secrets：

| Secret | 內容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 自己的 `.jks` 檔案經 Base64 編碼的完整內容 |
| `SIGNING_KEY_ALIAS` | 私鑰 alias |
| `SIGNING_STORE_PASSWORD` | keystore 密碼 |
| `SIGNING_KEY_PASSWORD` | 私鑰密碼；省略時沿用 keystore 密碼 |

Linux 可用 `base64 -w 0 /path/to/release.jks` 取得編碼。雲端會暫時還原簽章檔和兩個專案的 `local.properties`，結束時清除；本機檔案不會被上傳。

將工作流推送至預設分支後，在 **Actions → Build signed Android Release → Run workflow** 手動執行，或推送 `v*` 標籤觸發。一般分支 push 不會觸發簽章建置。

工作流固定檢出 `raptorz/TVBox-media` 的 `dfe2081eb30535ff71dda63dd4715e9a739511e3`，安裝 Java 21、Python 3.12、SDK／NDK／CMake，執行 Python 相容性測試和 mobile 快取測試，再編譯 mobile 與 leanback Release。更新 media 時，需同步修改工作流中的 commit。

執行成功後，從該次執行頁面的 **Artifacts** 下載 `tvbox-arm64-release-執行編號`，內含兩個已簽章 APK 和 `SHA256SUMS.txt`，保存 30 天。流程驗證 ARM64、APK 簽章及 16 KB ZIP 對齊。

推送 `v*` 標籤時，兩個版本建置及驗證成功後，會自動建立同名 **GitHub Release**、產生更新說明，並附上兩個 APK 與 `SHA256SUMS.txt`。附件完整上傳後才公開發布；重跑時可接續未完成的草稿，已公開的 Release 不會被覆寫。手動執行只上傳 Artifacts，不建立 Release。

發布使用 GitHub 自動提供的 `GITHUB_TOKEN`，只在發布 job 授予 `contents: write`，不需額外設定 PAT。

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=FongMi/TV&type=Date)](https://www.star-history.com/#FongMi/TV&Date)

## Python 相容性驗證

`chaquo/src/main/python/ujson.py` 使用標準庫實作 ujson 5.x 常用介面，支援緊湊輸出、HTML／斜線轉義、bytes 選項與檔案讀寫；未知或已移除的參數會報錯。浮點數的字串格式及自訂 `__json__` hook 不保證與原生 ujson 一致。

執行 `python3.12 -B -m unittest discover -s chaquo/tests -v`；在獨立測試環境安裝 `ujson==5.11.0` 後，也會執行原生實作對照測試。
