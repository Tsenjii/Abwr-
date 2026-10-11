## 0.10.0 — 2026-10-11（完整裝置回報修正）

- 手機 UI 重建底部工具列與卡片面板，繪製圖示，清楚標示目前分頁與容器；主分頁可以關閉。
- 文件 UUID、pendingUrl 與舊 bridge 拒絕；表單優先快照、label、open shadow root、分頁、獨立 iframe bridge。
- Native keyboard/touch 與完整快照 metadata；HTTP/MCP 參數驗證、隱藏/停用/唯讀拒絕、挑戰未知狀態。
- TextureView 前景與 GeckoDisplay/ImageReader 背景 Surface，截圖尺寸與文件/Surface 核對；受控模擬器驗收保存實際測試頁像素與 UI。
- production 固定憑證，developer package 獨立，distribution key 不得生成替代品；持久 Actions Secret 遷移尚待完成。
- 本機 Python 31 項/JS 四組 PASS；Android CI、Kotlin 24 項與模擬器待執行；真機待重驗。

## 0.9.0 — 2026-10-11（AI 通道／操作一致性）

- 人工接管同步持久化，程序恢復不默默交還 AI；回報儲存結果。服務沒有 Activity 的恢復也可重新安排已明確開啟的背景 renderer。
- 根據 CI 日誌修正 debug keystore 不存在的快取問題：明確生成並指定同一路徑，憑證指紋也出現在 build log。快取仍不是正式持久 release signing。
- Native bridge 每次斷線最多八次退避重連，pagehide 停止、pageshow 恢復；取消 DOM observer，不把舊 Port 的遲到回覆送到新 Port。
- `tab`、`expectedUrl` 可拒絕已切換的頁面；snapshot ref 在同文件網址改變時失效。status 可指定 profile，不改變可見容器。
- HTTP 三個 control workers／十二筆 queue，頁面工作兩個 workers／八筆 queue；耗時操作不佔用 status／takeover worker。
- MCP 分頁工具單序列、獨立控制佇列、ping／取消通知不受頁面呼叫阻塞；排隊中操作可取消，已送出副作用不能回滾或保證停止。
- MCP 保留穩定錯誤代碼、拒絕 redirect、JSON 非 object／socket timeout 正確回失敗，驗證參數類型。
- 純文字 contenteditable 輸入／清空，停用／隱藏／唯讀檢查；不再將 scroll y=0 變成 600，也不悄悄截短超量輸入。
- Python 30／Kotlin JVM 15／JS 三組／Android APK CI 全部 PASS，DEVICE PENDING。

## 0.8.0 — 2026-10-11（日常下載／登入提示／Session 可靠性）

- Gecko 原回應串流下載：確認、容器紀錄、進度、取消、開啟、分享、文件選擇器另存；保護下載中 Session，截斷／程序停止不假報完成。
- HTTP AuthPrompt、來源 Session 綁定的網站提示視窗與權限；Prompt 導覽消失時關 UI，檔案選擇支援多 MIME 與過期結果保護。
- 修正關 Tab 拒絕前誤移除 URL、Port 斷線/替換未即時失敗、跨 Session/Port 回覆、Popup navigate wait/父頁 metadata、Popup 關閉舊截圖。
- 截圖序列化、12 秒內結束、背景編碼與 Bitmap 回收；Surface/Page 改變即拒絕，VirtualDisplay 保留前景 viewport。
- HTTP workers queue 上限、503 BUSY、停止服務關閉連線；UI 逾時移除尚未執行工作、取消內層等待。
- 日常選單與淺／深／系統外觀；返回鍵依 Popup 歷史與額外分頁處理；新增容器略過已退休 ID。
- 新增 Kotlin JVM 行為測試；CI 產出公開 APK 簽章指紋並最佳努力快取 debug keystore。舊版私鑰無法據此復原，正式 V1 仍需持久簽章。
- SOURCE 完成；Python 23 項/Kotlin 13 項/JS/Android APK CI 全部 PASS；DEVICE PENDING。


CI 與 APK：[v0.8 完整建置紀錄](docs/V0_8_BUILD_RESULT.md)。v0.8.0 已經使用者同意合併 [PR #1](https://github.com/Tsenjii/Abwr-/pull/1) 至 main；後續版本請核對最新 HEAD。

## 0.7.1 — 2026-10-11（V1 交接／背景、登入、日常體驗）

- 新增完整繁體中文 `docs/V1_MASTER_HANDOFF.md` 和 `docs/V1_NEXT_STAGE.md`，直接供任意 AI 繼續 GitHub 開發，不限定 OpenCode。
- 背景渲染加入階段診斷、frame age、無 frame 的清楚錯誤、有限速重建私有 Surface。不是 OS 常駐保證。
- Android 外部 OAuth 登入備援只選其他瀏覽器，避免回到 Abwr 自己造成驗證迴圈。
- 書籤與歷史加入即時搜尋；分頁列表正常處理 Popup 關閉；主頁標題顯示不再以 AI 偵錯文字為主。
- `scripts/v1_smoke.py` 安全地執行本機診斷，可選擇只在記憶體驗證即時 JPEG，不落地圖片。
- 新增對應 Browser UI／HTTP/MCP 的回歸合約測試；真機 OAuth／Cloudflare／背景 screenshot 尚待實測。


## 0.7.0 — 2026-10-11（V1 穩定性收斂）

- 以真實 GeckoSession 綁定進行中的 WebExtension 指令，關閉頁面或 OAuth Popup 時立即回報失敗；不再浪費剩餘等待時間。
- GeckoSession LRU 不淘汰導覽 waiter 尚在等待的 Tab／Profile。
- 拒絕刪除尚有 in-flight Agent 操作或人工接管的容器。
- 容器刪除的舊 ID 持久保留不再重用，啟動時重試要求 Gecko 清理舊資料；現用 GeckoView API 不回報清理完成確認，不能宣稱已清除。
- 新導航、Tab／Popup 切換會清除前頁快取截圖；在截圖過程中切頁，回報明確錯誤。
- Popup URL／Title／Loading 與主分頁獨立追蹤，API 正確反映目前 OAuth 窗口。
- MCP 新增完整 stdio JSON-RPC handshake/tool registry/error 回歸測試，版本號統一。
- V1 Release Gate 以可測量的 DEVICE 驗收，不把 CI 通過當作真機成功。


## 0.6.2 — 2026-10-11

- 新增 Gecko 原生抗指紋保護全域開關，以及 Global Privacy Control。
- 每容器隱私報告顯示真實 Cookie/storage contextId 隔離及「共享 Runtime 指紋防護」的明確限制。
- 新增 GET /v1/privacy、POST /v1/settings/fingerprint-protection、MCP browser_privacy / browser_fingerprint_protection、Android UI 設定。
- 不實作指紋輪換、虛假裝置身份或網站身份／驗證規避；每容器獨立指紋尚未實現。


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
