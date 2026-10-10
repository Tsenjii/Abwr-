# Abwr — 建置與功能進度（2026-10-11）

目前：**程式碼在 main；APK 尚未成功編譯或真機測試**。

## 已執行的 CI
1. [第一輪 #38077756568](https://github.com/Tsenjii/Abwr-/actions/runs/38077756568)：setup-android 安裝過時 tools 套件失敗。
2. [第二輪 #38077838078](https://github.com/Tsenjii/Abwr-/actions/runs/38077838078)：SDK 安裝成功，但 GeckoView 157 / core-ktx 1.19 要求 API37/AGP9.1。
3. [第三輪 #38078020438](https://github.com/Tsenjii/Abwr-/actions/runs/38078020438)：SDK 套件名稱應為 android-37.0 而非 android-37。
4. [第四輪 #38078117398](https://github.com/Tsenjii/Abwr-/actions/runs/38078117398)：已完成 SDK 安裝、Python 與 JS 測試；Kotlin2.2 編譯器無法讀取 GeckoView/AndroidX 2.4 metadata。
5. **修復中**：使用官方 AGP9 建議的 buildscript classpath 將內建 Kotlin Gradle Plugin 升級為 2.4.20。

## 功能與真機狀態
| 項目 | 狀態 |
|---|---|
| GeckoView 引擎／容器 contextId | 原始碼 |
| DOM、點擊、輸入／WebExtension | 原始碼；JS mock |
| HTTP API + token、MCP bridge | 原始碼；Python 基礎測試 |
| 接管、彈出視窗 | 原始碼；未真機驗證 |
| 背景截圖 | 未完成 |
| 代理 Proxy | 未實作 |
| Google OAuth 與 CF 驗證 | 未驗證（不承諾規避檢查） |
| 100+ 帳號 | 僅 metadata 目標，未真機壓力驗證 |
| APK | 只有編譯成功並上傳 Artifact 才為完成 |

維護要求：每次改動更新 docs/PROGRESS.md 與 docs/HANDOFF.md；禁止把 source-only 當成 device-pass。
