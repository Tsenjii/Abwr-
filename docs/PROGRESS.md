# Abwr — 建置與功能進度（2026-10-11）

**階段：GitHub Repo 已提交，CI 排錯中，尚未有經驗證的 APK。**

## 已執行
- 已上傳 Kotlin GeckoView 專案、WebExtension、HTTP 與 MCP、繁體中文文件。
- Python unit tests / Node JS bridge tests 在 GitHub Actions 通過。
- 第一輪 CI #38077756568：setup-android@v3 嘗試安裝已不存在的 sdkmanager tools package 而失敗。
- 第二輪 CI #38077838078：SDK 與 Gradle 安裝成功，程式編譯未開始前因 AAR metadata 失敗。原因：GeckoView 157 / androidx.core 1.19 需 compileSdk 37、AGP >=9.1。
- 第三輪：已升級 AGP 9.1.1 + built-in Kotlin + API 37 + Gradle 9.3.1，待實際 CI 驗證。

## 功能狀態
| 功能 | 狀態 |
|---|---|
| GeckoView 核心／容器 contextId | 原始碼 |
| DOM、點擊／輸入、WebExtension | 原始碼 + mock |
| HTTP 本機 API + Token | 原始碼 |
| MCP stdio bridge | 原始碼 + Python basic tests |
| 手動接管與 popup | 原始碼 |
| 100+ 容器與背景運行 | 尚未真機驗證 |
| 代理設定 | 未實作 |
| Android 真機 | 未實測 |
| Google OAuth / Cloudflare 驗證 | 未實測；不得宣稱可繞過 |
| APK build | 以最新 Actions 結果為準 |

只允許在 GitHub Actions 回報成功且具有 APK artifact 後才寫 CI PASS；原始碼與真機驗收必須分開。
