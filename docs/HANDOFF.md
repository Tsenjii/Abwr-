# 工程交接

Repo: https://github.com/Tsenjii/Abwr-
平台：Android Kotlin / GeckoView / Local HTTP API / MCP bridge

## 目前狀況
此 Repo 來自 v0.2 原始碼。是否可安裝請以 GitHub Actions 的真正 Build 結果為準，不得直接認定完成。
原始碼有 GeckoRuntime、profile contextId、WebExtension native messaging、HTTP loopback、API token、Android UI 人工接管。背景截圖、Proxy、OAuth 相容性尚未實測完成。

## 下一步（嚴格順序）
1. 檢查最新 GitHub Actions 執行紀錄，修正 Kotlin/AGP/GeckoView 編譯錯誤。
2. 必須實際產出 debug APK，保留 build log / artifacts。
3. 真機測試 Service 啟停與端口 127.0.0.1:8765。
4. 驗證 GeckoView 內容腳本 native messaging。
5. 先測兩個 profile 的網站資料隔離及簡單網站 DOM 操作。
6. 驗證人工接管、截圖及 popup。
7. 壓力測試與 Proxy 才進入下一階段。

## 規範
- 不把未測功能標為可用。
- 不把 Google OAuth 或 Cloudflare 人機驗證的繞過當作已完成能力。
- 代理設定不能為了通過安全檢查偽造裝置身分。
- 每次提交更新 docs/PROGRESS.md 和 CHANGELOG.md。
- 不儲存或提交使用者密碼、Cookie、Token。
