# CHANGELOG

## v0.2.0-source — 2026-10-11

- 完整繁體中文目標、架構、進度、交接、驗收和 API 文件。
- Profile 指定操作不再自動切換 UI；每個 profile 獨立的人工接管鎖。
- Gecko context ID 資料隔離與刪除資料 API；主 Session + 單一 OAuth popup 原型。
- DOM links/wait/select/focus/press 和元素座標資料。
- HTTP API 端點新增：profile/delete、tabs、close_popup、back/forward/reload、screenshot/full。
- MCP 工具擴充，支援指定 profile 與全頁截圖。
- 說明保留尚未完成：真機編譯、背景截圖、代理、下載上傳、Google OAuth 相容性。

## v0.1.0-source — 2026-10-11

- 初始 Kotlin + GeckoView + API + native WebExtension + MCP stdio 原始碼原型。
- 不含 Android APK；只有基礎 Python / Node 測試。