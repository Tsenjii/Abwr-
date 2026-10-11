
## 0.3.2 — 2026-10-11
- 修復 4+ 工作階段使用時，LRU 可能關閉正在操作 session 的問題；允許短暫超過 residentLimit，指令完畢再回收。
- JPEG 最多快取 3 張、4 MiB；加上背景私有 VirtualDisplay 實際影格數與最後畫面年齡的診斷。
- `GET /v1/diagnostics` 含 Java heap、battery optimization 狀態、背景渲染錯誤與影格。
- `navigate` 可帶 `waitForLoad:true`，等待 Gecko 原生進度回報，減少 Agent 人工猜 sleep 時間。
- 人工接管模式中保留 App 使用者直接操作，Agent 的 back/forward/reload 遵守手動接管鎖。
- 加入需要使用者確認的 API Token 重設按鈕；資料不清除。
- 改善 HTTP request parsing 的記憶體配置與不正確 Content-Length 檢查。
- 增加 JS 動態橋接測試及 Python 原始碼契約測試。背景真機效能仍未驗證。

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

## 0.3.1 — 2026-10-11

- 加入預設關閉的私有 VirtualDisplay/Presentation 離屏 GeckoView 方案，供 Android 背景截圖實驗。
- 保留快取截圖的時間戳與新舊畫面區分；Android 真機背景成功率仍待實測。
- Android CI 編譯與測試成功：[Actions #38109117417](https://github.com/Tsenjii/Abwr-/actions/runs/38109117417)。

## 0.3.0 — 2026-10-11

- 更正專用瀏覽器服務的 Android Foreground Service 類型與通知，新增系統背景執行設定入口。
- 新增 /v1/command navigate/goto/open/load/back/forward/reload；新增複數形式 Profile CRUD API 別名。
- WebExtension 新增 clear、toggle、keyboard、scrollTo，find 加入 text/role/ariaLabel。
- 新增 /v1/screenshot/last、/v1/screenshot/meta（快取標為舊畫面），與即時截圖區隔。
- Android CI 編譯與測試成功：[Actions #38108973660](https://github.com/Tsenjii/Abwr-/actions/runs/38108973660)。
