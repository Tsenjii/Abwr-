
## 0.6.1 — 2026-10-11

- 新增 GeckoView PromptDelegate：正常 JS alert/confirm/prompt、未儲存頁離開確認、重送表單確認、Popup 明確授權。
- 整合 Android 文件選擇器至 Gecko FilePrompt（單／多選，使用者觸發）。
- PermissionDelegate 基本網站權限/Android runtime 權限由使用者確認，不允許 Agent 自動授權設備能力。
- 增加顯示 Google/Cloudflare 網站登入相容診斷的 UI；HTTP `compatibility` 與 MCP `browser_compatibility`。
- 加入系統瀏覽器人工登入備援。Google 官方嵌入式 OAuth 政策與 Cloudflare 驗證伺服器限制不可能由 Abwr 無條件保證放行。
- CI 編譯與真機驗證需分開記錄。詳見 docs/LOGIN_COMPATIBILITY.md。


## 0.6.0 — 2026-10-11

- 對整個 Repo 的 Android/Kotlin、WebExtension、Python MCP、Manifest、CI、測試及文件進行靜態稽核；建立 `docs/CODE_AUDIT_2026-10-11.md`。
- Android 日常瀏覽器加入原生 Gecko Finder 頁內查找與高亮、前後查找。
- 新增每容器 GeckoView Desktop UA/Viewport、可選搜尋引擎 Google/DDG/Brave/Bing。
- 支援 Android 外部 http/https ACTION_VIEW 連結、前後頁導航可用性指示、系統返回鍵先處理 popup/網頁歷史。
- Foreground Service 通知可直接回到 Abwr，不在通知顯示 Token。
- 增加對應通用 HTTP/MCP 工具，以及跨 Agent 的通用 Skill/AGENTS.md，不再限定 OpenCode。
- 保留 Token 安全界線，不匯出其它 App 之 Cookie；背景即時截圖與 OAuth 未視為已驗證。


## 0.5.1 — 2026-10-11

- 新增高速 Agent 頁面讀取：`action=read` / `browser_read`，以有上限的正文、標題、meta、headings 代替大型 DOM。
- 導覽等待綁定 GeckoSession，避免其他分頁 PageStop 誤觸成功；分頁載入狀態獨立。
- `GET /v1/status` 不再有開 Session 的副作用；節省操作時多餘載入。
- extra tabs 新增 popup OnNewSession；Tab LRU 開頁時保護目前 Session。
- 人工接管阻止 Agent 分頁增刪切換，但使用者 UI 不受影響。
- App 增加細載入條、複製／分享 URL 選單。
- MCP URL 主機嚴格驗證，防止惡意 userinfo hostname 收到 Bearer Token。
- 增加 WebExtension / Python 回歸測試；CI 與 Device 驗收分開記錄。


## 0.5.0 — 2026-10-11
- 全面改造 Android 手機前端，接近日常瀏覽器的網址列／分頁／選單架構，移除佔畫面的 API 文字和大量調試按鈕。
- GeckoSession 真實多標籤頁，網址／開啟分頁狀態保存；有額外常駐數與上限。
- 書籤與分容器歷史記錄、HTTP API、MCP 工具。
- GeckoView ETP 追蹤保護，使用者可開關；DevTools 網頁級摘要及同來源 Storage 檢視（憑證遮蔽）。
- 新增 OpenCode/Termux Agent Skill，保持本機 MCP 以及必要時人工接管。
- 未完成：全功能 Brave Shields／CDP、原始 Cookie 匯出、下載管理、Google OAuth、保證背景即時截圖。


## 0.4.0 — 2026-10-11

- 以 Playwright MCP / agent-browser 的 Agent 工作流程為目標；WebExtension 新增 `snapshot` + 單次快照有效的 `eN_N` 元素參照、角色標籤、輸入框值遮蔽及舊 ref 明確報錯。
- click/type/select/focus/clear/toggle/keyboard 支援 snapshot ref 或 CSS selector。
- `wait` 增加按頁面可見文字等待；`type` 可選 `submit:true` 使用關聯表單 `requestSubmit()`。
- MCP stdio bridge 擴充至約 30 個操作及狀態工具；HTTP 4xx/5xx 與 DOM `ok:false` 以 MCP `isError:true` 回報。
- 新增 `GET /v1/capabilities`，明確告知 Agent 支援／實驗／未支援的能力。
- 新增 JS snapshot refs 實際 mock 測試及 Python MCP 端點與錯誤處理測試。
- 不宣稱已擁有完整 Playwright 無障礙樹、任意 JavaScript eval、Network/CDP、下載上傳或保證可用的背景截圖。


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
