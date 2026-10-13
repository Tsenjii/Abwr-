> **2026-10-14 最新驗收修正：** [精確閒置與產品交接](V0_14_1_IDLE_RESULT.md)。v0.14.1 固定簽章產品已發布並通過兩個 Android 系統的覆蓋更新；舊 1／10／30 分鐘資料是累計檢查點。PR #7 已通過兩個 Android 系統各 22 組與獨立 60／600／1800 秒閒置，本次出口 IP 的回歸測試亦通過，發布須保留各來源的證據。

> **v0.14.1 接手入口：[完成狀態與下一步](V0_14_1_COMPLETION.md)／[APK 與證據](V0_14_1_BUILD_RESULT.md)。** code 24／schema 4／82 tools；Python 68／JS 六組／JVM 63／固定簽章 Release PASS，Android13/API33 與 Android15/API35 各 22 PASS，含保留資料升級與 1/10/30 分鐘鎖屏。產品 source `e20ffc97559a2857acb5e5bd64b0d8b1b0f3f153`，CI 38426201545 SUCCESS。AI／UI 共用普通權限；SHARP 真機及 15 真實獨立出口 NOT_RUN，非正式 V1。以下較舊版本紀錄保留為歷史。

> 接手先讀 [v0.14 目標與進度](AI_HANDOFF_2026-10-13.md)、[權限共用契約](V0_14_AUTOMATION.md)、[產品 APK 與證據](V0_14_BUILD_RESULT.md)。0.14.0／code 23／schema 4／82 tools；SOURCE 與 CI 固定簽章產品 22 組／1/10/30 分鐘鎖屏 PASS。AI/UI 共用一般權限，顯式接管保留。SHARP DEVICE 未測，非 V1；以下舊版紀錄為歷史。

## 最新交接：v0.11.0（2026-10-12）

先讀 [本輪建置與失敗修正紀錄](V0_11_TAB_GROUPS_BACKGROUND.md)、[本地 AI 驗收 Prompt](V0_11_ACCEPTANCE_PROMPT.md) 及 [通用 Skill](../skills/abwr-browser/SKILL.md)。下載 APK source `4c52da9150b2e4c01493b7e303a19c8cdc9804e9`，CI 38171622490：Python 37、JS 四組、JVM 37、Android Debug/Release 與永久簽章 PASS，11 組快速操作回歸 PASS。完整基準 `e3d616c3877dc160756c38194f336e608ec5488a`／CI 38166025803 的 12 組、HTTP/MCP 各 50 輪、UI 保存及 1/10/30 分鐘關螢幕 PASS；修正版此輪長測 NOT_RUN。各輪證據不混用成另一 APK 全測 PASS。SHARP v0.11 待驗，非 V1。App/API/MCP/content bridge 都應為 0.11.0，工具 59 個；更新 APK 後也更新本地 adapter 並重啟 MCP client，安裝不會自動連 MCP。

下方各版本是歷史紀錄，以本段、Git HEAD 及本輪證據為準。

---

## 最新交接：v0.10.0（2026-10-11）

先讀 [建置結果](V0_10_BUILD_RESULT.md)、[完整回報修正與本地 AI Prompt](V0_10_ACCEPTANCE_FIXES.md) 及 [通用 Skill](../skills/abwr-browser/SKILL.md)。Python 35/JS 四組/Kotlin 24/Android Debug 與 Release/八組模擬器全部 CI PASS，四個 UI 畫面已核對；HTTP 50 輪與實際 MCP 工作流是不同的測試範圍。SHARP 真機、鎖屏及長時間驗證未完成，非 V1。

v0.9 signing key 已回收及私密備份，永久 Actions Secret 已設定並以實際 CI 驗證 release 憑證一致；cache fallback/臨時匯出已移除，缺 Secret 或不同憑證不准 release。產品下載 `Abwr-signed-apk`；`.dev` 只供測試。真機覆蓋更新保留資料仍待驗。以下為歷史交接，不代替最新結果。

---

## v0.9.0 — AI 通道與操作可靠性（2026-10-11）

v0.8.0 已合併 main。本輪新增橋接有限重連、BFCache 回復、等待 observer 清理、分頁／網址操作前置條件，以及 HTTP/MCP 獨立控制工作佇列。可編輯文字區支援純文字輸入，隱藏／停用／唯讀欄位會拒絕操作；等待失敗與連線錯誤保留機器可讀代碼。未自動重送已發出的點擊或提交。

Python **30** 項、Kotlin JVM **15** 項、**三組 JavaScript**、Android APK 編譯及上傳全部 CI PASS。**沒有新增 DEVICE PASS，仍非正式 V1。** 完整設計、限制與手機驗收步驟：[v0.9 AI 可靠性紀錄](V0_9_AGENT_RELIABILITY.md)。

完整 CI：[Actions #38127631965](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965)；[下載 APK](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965/artifacts/11697644033)。程式碼 commit `c9447b8b70790cbb647e11f12c0a49b80fd06f5a`，後續文件提交不改變已驗證程式碼。

---

## 最新交接：v0.8.0（2026-10-11）

先讀 `V1_MASTER_HANDOFF.md`、`V0_8_DEVICE_CHECKLIST.md`；這版 SOURCE 已完成，Python/JS 本機 PASS、Kotlin/Android CI PASS、DEVICE PENDING。

新增 `BridgeRequests.kt`（Session + Port 對應與即時失敗）、`BrowserDownloads.kt`（原 Gecko stream／本機紀錄／取消與另存）、`DownloadIO.kt`（安全 filename、有限 buffer、截斷與取消驗證）及 13 項 JVM 測試。Controller 保護下載 Session，Popup navigation waiter 與父頁 metadata 分開，截圖檢查原 Surface/Session/Page 並背景編碼；LocalApiServer 有 12 筆等待 queue 和 503，uiAsync 用一個總時限。

MainActivity 管理 Prompt 的來源與取消，補 HTTP Auth、多 MIME picker、下載界面、日常選單和外觀。`lastBlockedSiteInteraction` 記錄被隱藏頁面請求拒絕，不是已批准權限。不要將 CI 當背景/GitHub/Google/CF 真機證據。

下載已開始後切背景可繼續串流（需裝置驗證），背景新下載仍需本人回前景確認；程序被停止不自動續傳。覆蓋安裝先對 APK 簽章，CI debug key 快取是最佳努力、不可取代正式持久 release signing。


CI 與 APK：[v0.8 完整建置紀錄](V0_8_BUILD_RESULT.md)。v0.8.0 已經使用者同意合併 [PR #1](https://github.com/Tsenjii/Abwr-/pull/1) 至 main；後續版本請核對最新 HEAD。

---

# Abwr AI 工程交接文件（2026-10-11）

Repo：https://github.com/Tsenjii/Abwr-

## 最重要的現況
- **已成功編譯 Debug APK**：[GitHub Actions #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)，Artifact 名 `Abwr-debug-apk`。
- **部分真機驗證成功（2026-10-11）**：Android 可安裝／顯示網頁；/health、Bearer Token /v1/status、G001 /v1/command action=dom 都已由使用者提供 Termux 實測結果。`ready:true`、`extensionPortReady:true`，成功取得 Example Domain 標題、正文、IANA 連結與元素位置。詳見 docs/PROGRESS.md。
- **未完成的真機驗證**：click/type/scroll、畫面截圖、人工接管、profile 儲存隔離、MCP tool calls、OAuth、多帳號壓測。不要告知使用者「全部功能可以用了」。
- Android GeckoView/Kotlin 本機 Agent Browser Server，非一般聊天 App。獨立於 Chrome/Brave 工作階段。
- 重要未完成：真正背景截圖、全頁截圖、原生 Proxy、Google OAuth相容性、100+ 持久登入壓測。
- 使用者希望能在同一個 AI Session 人工接管登入並交還 Agent；HTTP/MCP 不控制自己的 Chrome/Brave UI。

## 專案布局
- `app/src/main/java/dev/local/aibrowser/BrowserController.kt`：GeckoRuntime、contextId、session、native messaging、命令、螢幕截圖。
- `app/src/main/java/dev/local/aibrowser/LocalApiServer.kt`：127.0.0.1:8765 + bearer token。
- `app/src/main/java/dev/local/aibrowser/MainActivity.kt`：人工作業／多帳號管理。
- `app/src/main/java/dev/local/aibrowser/BrowserService.kt`：Android foreground service。
- `app/src/main/assets/agentbridge/`：WebExtension 內容腳本、DOM 指令。
- `bridge/mcp_stdio.py`：MCP stdio 協定橋接。
- `docs/API.md`：端點說明。
- `.github/workflows/build-apk.yml`：GitHub Actions 建置和 artifact。

## 下一步（按優先度）
1. **已通過：** APK 安裝、GeckoView example.org、/health、Bearer Token /v1/status、WebExtension action=dom。
2. **下一步：** 利用普通測試網站驗證 WebExtension click/type/scroll、等待元素、一般頁面截圖。
3. **再來：** 人工接管與交還 Agent，確認同 Session 不搶操作。
4. 驗證兩個 contextId 的登入資料與 Cookie 狀態確實隔離；再測 MCP stdio 真實 tool calls。
5. 測試從網站的 OAuth popup 及回跳是否會失敗；Google 可能拒絕內嵌登入，不可繞過其安全限制。
6. 修正背景渲染與 screenshot；再做可選代理配置。
7. 後續測試 10/30/100 個 profiles 的 RAM／效能與儲存穩定性。
8. 每次改版更新 docs/PROGRESS.md、docs/HANDOFF.md、CHANGELOG.md。

## 建置版本
AGP 9.1.1 + Gradle 9.3.1 + Kotlin Gradle Plugin 2.4.20 + compileSdk 37.0（Kotlin DSL `release(37)`）、JDK17、GeckoView 157。CI 連續排錯記錄見 PROGRESS.md。

**注意**：GeckoView 的 `captureFullPage()` 並不存在於目前編譯使用 API，原本的 full-page handler 已明確回報不支援；只有可見畫面 `capturePixels()` 編譯通過且仍需實測。Proxy HTTP endpoint 未實作，不能宣稱成功啟用。不要憑網站表示的「已登入」推測與 Google OAuth 相容。


## 新增交接 — v0.3.1（2026-10-11）
- 背景常駐：`BrowserService` 目前宣告合法用途的 `specialUse` foreground service，移除誤用的 `dataSync` 類別，避免 Android 15 該類別 6 小時限額；仍受 OEM/Doze/force-stop 限制。
- 真正即時背景截圖：`OffscreenBrowserDisplay.kt` 已**實驗性編碼**，在主 Activity onStop 建立私有 VirtualDisplay + Presentation + GeckoView，onStart 切回真實 Activity；由 App 內「背景截圖實驗模式」明確啟用，預設關閉。CI 跟真機必須分開驗證。
- `/v1/screenshot` 嘗試即時擷取；`/v1/screenshot/last` 只是快取（可能過期），`/v1/screenshot/meta` 有拍攝時間及 ageMs。
- 舊版 Agent 報告導航、容器不能用，多半因請求路徑不符。v0.3 新增 `POST /v1/command action=navigate/back/forward/reload` 與 `POST /v1/profiles`、`/v1/profiles/create/switch/delete` 別名；舊 API 仍有。
- 表單已新增 clear、toggle、keyboard（只是 DOM synthetic keyboard event，不等於真正硬體鍵盤）、scrollTo；find 支援 text/role/ariaLabel。禁止聲稱已可操作任何網站。
- **最重要未完成**：設備實際驗證虛擬畫面輸出、長時間存活、Google OAuth、Profile 持久隔離、100+ profiles、下載上傳、HTTP status/errors 完整化。不要為了求通過驗證而建立 CAPTCHA 自動繞過或 IP 偽裝。
- 每輪重要進展，更新 `docs/PROGRESS.md`、`docs/BACKGROUND_AND_AGENT_ROADMAP.md`。


## v0.3.2 本輪工程交接
- `BrowserController` 中新增 `inFlightByProfile`；LRU 的 `pruneSessions(preserveId)` 不能關閉活躍指令與 UI 活躍 profile。長期同時操作多個 profile 可能使 residentSessions 暫時超過 3，指令完成才淘汰。
- `navigateAndWaitAsync` 透過原生 Gecko `onPageStart/onPageStop` 完成，獨立於 WebExtension 的 DOM 連線。它**不是** HTTP status API；它依頼符合請求的 onPageStart 才完成，站點跳轉仍需真機測試。
- `OffscreenBrowserDisplay` ImageReader 回報 framesReceived / lastFrameElapsedMs；這是 VirtualDisplay 收到的畫格，不保證整個網頁是正確或沒有黑畫面。
- `GET /v1/diagnostics` 回報 Java heap / batteryOptimizationExempt、快取錯誤、背景畫格；不要把 heap 當總 App RSS。截圖快取最多 3 張總 4MiB。
- `LocalApiServer.rotateToken` 只能由 App 內的確認對話框觸發 `BrowserService.ACTION_ROTATE_TOKEN`；舊的 Agent token 即刻失效。既有登入資料不清除。
- `MainActivity` 使用者 UI 直接調 `humanBack/humanForward/humanReload`，Agent back/forward/reload 受 takeover 保護。
- `tests/bridge_behavior_test.js` 會執行真實 content.js 的 Native Messaging mock，非僅字串包含；`tests/test_protocol.py` 增加 API 路由的基本守門檢查。


## v0.4 — MCP / Snapshot 對齊工作
- `app/src/main/assets/agentbridge/content.js` 的 `snapshot` 返回短文本及 `refsVersion`；每次刷新 `snapshotRefs` 清空，舊 ref 明確失效。snapshot 是 DOM 近似角色清單（不等於 Playwright accessibility tree）；使用者輸入密碼不能在快照中顯示值。
- `BrowserController.commandAsync` allow-list 包含 `snapshot`；手動接管中 snapshot/DOM 只讀仍可執行，click/type/clear 等寫入受限。
- `bridge/mcp_stdio.py` 有 `browser_snapshot`、`browser_click` (target/selector)、`browser_type`、`browser_select`、`browser_wait` 等；Python 標準庫即可執行，`AI_BROWSER_TOKEN` 需本機取得。MCP tool error 和 JSONRPC protocol error 應分清。
- `GET /v1/capabilities` 回報能力標籤供本地 Agent 自我發現；未實作的 Generic JS Evaluate/Network/Download 不得宣稱完成。
- 實際 Android、OAuth、長時間背景截圖、100 profile session 試驗仍需裝置驗收，CI passing 不等同 Device passing。


## v0.5.0 — 日常瀏覽器介面／Tab／Storage / Skill 交接
- Android MainActivity.kt 重寫為瀏覽器介面，選單包含追蹤防護、DevTools、Storage、Cookie 名稱、書籤、歷史、AI 接管與 Token 管理。
- BrowserController.kt 的 extra tabs 是 GeckoSession，與同 profile 的 main session 使用同 `contextId`；共享登入資料，但不與 Chrome/Brave 共享。Tab metadata、bookmarks、history 存入 browser_profiles SharedPreferences；程序重啟後 URL 恢復，不保證 SPA 表單狀態恢復。
- `runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel` 切換 GeckoView ETP。與 Brave Shields 不能畫上等號。
- WebExtension `devtools` 只包含頁面 JS 錯誤、performance resource timing、DOM 元件計數；`storage_inspect` 僅同 origin 值，secret pattern 被遮蔽；`cookie_metadata` 不回傳 Cookie 值。
- `.opencode/skills/abwr-browser/SKILL.md` 供 OpenCode 自動發現／載入，其他 Agent 可複製到自己 global skills 目錄。
- 重要後續：多 tab session 時 popup onNewSession、active tab 與 navigate waiter 的一致性；真機查 ETP 是否造成 Google / OAuth 失敗。 Android CI 不能驗證登入 Cookie 和背景顯示。

## v0.5.1 優化交接

- Controller: `PendingNavigation` 加 session identity，NavigationDelegate 事件要同一 GeckoSession 才能完成 waiter。Extra Tab 使用 `tabLoading`，status extensionPortReady 不再 lazy instantiate（節省 RAM / 避免衝突）。
- Extra Tab 的 target=_blank 現走 `newPopupSession(profile)`，需真機驗證 OAuth callback/popup lifecycle。
- `pruneTabSessions(preserveKey=...)` 開新頁先保護新 Session；`releaseInFlight` 同時修剪 Main/Extra LRU。
- Tabs create/select/close 有 `fromUser` 旗標，API 呼叫預設 false、App UI true；人工接管模式只有 user 自身可以操控 tabs。
- WebExtension 新 `read`；read 通道由 allow-list 開放，MCP 增加 `browser_read`。更新 Skill 指導先 read 再 snapshot，降低 token 成本。find 可對原生元素推斷 role。
- MCP Host Check 須解析 URL authority，不可只用 startsWith 檢查，避免 userinfo 注入洩漏 bearer token。
- UI 檢視載入進度，分享/複製網址。Android 端目前沒有跑真機，自動化測試與 CI 結果仍應分開記錄。



## v0.6.0 — 通用 Agent / 一般瀏覽器功能交接

- 通用 Agent Skill 在 `skills/abwr-browser/SKILL.md` 與 `.agents/skills/abwr-browser/SKILL.md`；OpenCode 現有專屬 Skill 保留不受影響。任意 agent 需有同手機 MCP stdio 或授權 localhost HTTP 能力，並自己設置 Bearer Token。
- Gecko SessionFinder 是原生 Find-in-page（不是 WebExtension `find`）。`BrowserController.findInPageAsync` 的 Future 回實際結果 current/total；`clearFind` 刪除高亮。
- Desktop mode 屬於**每 profile** UA/viewport 參數，儲存在 `desktop_profiles_v1`；切換會更新該 profile 已載入 sessions，但僅可見頁重載以節省 RAM/避免打斷表單。
- 搜尋引擎選項使用 `BrowserSearch` 集中處理 URL/bar 搜尋，選項在 `abwr_settings.search_engine` 中保存。
- MainActivity 可收到外部 ACTION_VIEW http/https Intent，另開分頁，不會篡改其他 App 資料；Manifest 只是成為候選瀏覽器，使用者自行決定預設。
- Android 返回鍵處理 popup→網頁 history→退出 App；前後頁按鈕取 Gecko 的 onCanGoBack/onCanGoForward 真實事件，WeakHashMap 避免 evicted session 記憶體保留。
- 未解決：Gecko PromptDelegate（一般 JS alert、站點權限、檔案選擇）、外部下載管理、Google OAuth 真機等見 CODE_AUDIT_2026-10-11.md。



## v0.6.1 Website compatibility and login prompt handoff

- `BrowserController.sitePrompts` and `sitePermissions` are installed on all active GeckoSessions from `installSessionDelegate`; live `MainActivity` implements `SitePromptHost`.
- Covers user-facing JS alert/confirm/prompt, popup-block prompt, before-unload, POST repost, Android system file picker and site content permissions; requests dismissed/rejected when no visible host rather than silently granted.
- Permissions: Android location, camera and microphone only after OS runtime permission; websites request content permission separately. Media device source selection may need further implementation; do not claim complete WebRTC.
- Human manually opens system browser if Google rejects embedded OAuth. This does not transfer cookies or guarantee site callback.
- WebExtension `compatibility` and MCP `browser_compatibility` are read-only heuristics for challenge/error text. Cloudflare verification always follows site-provided normal flow; no CAPTCHA solver or account checks bypass.
- **CI and Device status must be recorded separately**. Read `docs/LOGIN_COMPATIBILITY.md`.


## v0.7.0 交接 — V1 穩定性（2026-10-11）

- 編譯 PASS：[Actions #38120934637](https://github.com/Tsenjii/Abwr-/actions/runs/38120934637)，來源 `0747b9884639e46dbbc6b8b4497fabe5aee97504`，Artifact `Abwr-debug-apk`。
- Controller 的 `requestSessions` 把待完成 WebExtension 指令關聯到 GeckoSession；關閉 Tab、關閉/替換 Popup 或回收 Session 時透過 `failSessionRequests` 完成失敗，不等待自然 timeout。
- `hasPendingNavigation` 防止 LRU 淘汰正在等待載入的 GeckoSession；`screenshotGeneration` 讓切換分頁/導航後的舊 JPEG 失效，避免跨頁誤讀。
- Popup 另外保存 `popupUrls` / `popupTitles` / `popupLoading`；`/v1/status` 與 `/v1/tabs` 回報彈窗的實際情況。
- 已刪除的 profile ID 永久保留於 `retiring_profiles_v1`，防止清除未完成時同名重建；GeckoView 157 的 `clearDataForSessionContext` 只有 Unit 回傳，**不能宣稱清除已確認完成**，啟動時仍會重試要求清理。
- `tests/test_mcp_stdio_e2e.py` 驗證 MCP stdio initialize/tools/list/call-error/ping；CI PASS 不等於 Android Device PASS。
- V1 需依 `docs/V1_RELEASE_GATE.md` 驗收背景長時間、實際登入、OAuth、容器隔離和截圖。不要改寫成所有 P0 已完成。


## v0.7.1 給接手 AI 的交付摘要

開始工作前先讀 `docs/V1_MASTER_HANDOFF.md`、`docs/V1_NEXT_STAGE.md`、`docs/V1_RELEASE_GATE.md`。
1. `BrowserController.backgroundRenderPhase()` 在 status/diagnostics 回報真實 Surface/frames 狀況；`recoverBackgroundSurface()` 是 20 秒限速、使用者/本機 Agent 明確呼叫、重新建立私有 VirtualDisplay 的 best effort，非 headless 保證。`captureAsync` 在已附著但 7 秒未收任何 frame 時提早提供修復訊息。
2. `POST /v1/background/recover`、MCP `browser_background_recover`。新工具只是重建自己的 display，不做其他 App 的畫面。
3. `MainActivity.showTabs` 已修 Popup 被當成 Tab 刪的問題，`showUrlCollection` 有搜尋欄，頂部顯示網站標題；外部 OAuth 備援用 PackageManager 只選別的瀏覽器，Manifest `queries` 宣告 https browser visibility。
4. 本機設備測試：在 Termux 設 `AI_BROWSER_TOKEN` 後執行 `python3 scripts/v1_smoke.py`，可加 `--screenshot`（只驗證、不落地）。
5. Android CI 成功不等於 GitHub/Google/CF 或背景真機驗收通過。下一個工程師應針對以上項目接著改善、找 Device 證據，避免新增一堆空 API。

## v0.7.1 完整交接入口與編譯結果（2026-10-11）
- **主控**：`docs/V1_MASTER_HANDOFF.md`；下一階段：`docs/V1_NEXT_STAGE.md`；發布條件：`docs/V1_RELEASE_GATE.md`。
- 本輪 Source commit `dc003a34d62d360a8fa7e5edd8d02e34896ccdf4`，CI PASS [#38122221612](https://github.com/Tsenjii/Abwr-/actions/runs/38122221612)，Artifact `Abwr-debug-apk`。
- 記得區分 Device 未驗證項目與 CI 已通過，尤其背景 Surface、OAuth、Cloudflare、長時間 Service 存活；新端點 `POST /v1/background/recover` 限 20 秒重試。
- 裝置自助診斷：`python3 scripts/v1_smoke.py`（使用者本機設定 Token），可選 `--screenshot`，不保存私密畫面。

