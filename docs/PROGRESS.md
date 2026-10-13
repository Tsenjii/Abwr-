## v0.14.0 — 自動化與共用權限（正在驗收）

最新完整綠色候選 1e1467dc／CI 38374290710：Python 61／JS 六組／JVM 61／Debug、core 16 組＋100 HTTP／100 MCP、browser 五組、automation 四組全部 PASS。新原語、15 profiles、代理 A/B HTTP／SDK 本文＋事件／新 tab／HTTPS／SOCKS／失效不直連均實證。再補儲存匯出精確布林同意、完整 scope 與未知欄位拒絕，新增兩項 JVM、實際遮蔽 export／audit／唯讀探測／links 分頁驗收，等待新 head；產品固定簽章與本版 1/10/30 分鐘長測仍待完成，SHARP 未測，非 V1。接手見 [repo 交接檔](AI_HANDOFF_2026-10-13.md)，手機步驟见 [本版契約](V0_14_AUTOMATION.md)。

最新產品 source 9112240／CI 38377804963：固定簽章 Release／JVM 63、舊 18 組、15 profiles／代理 PASS；逐鍵輸入在系統另存後 name 仍空白 FAIL，長測未跑。正在恢復 Android View／Gecko native focus 並加系統畫面往返與 all 組整合測試；本版產品尚未整輪通過。

## v0.13.0 — 日常瀏覽器功能補齊（工程候選版）

新增無痕瀏覽、關閉分頁復原、原生拖曳、影片全螢幕、網站連線／權限管理、書籤 HTML 匯入匯出、PDF／JPEG／文字另存、閱讀檢視、文字縮放與主畫面捷徑。v0.12 的擴充商店、隔離使用者腳本、原生 DevTools、HTTP 請求／二進位／真正串流及容器修正一併接續。

Python 37／JS 六組／JVM 55、Debug／Release 與固定簽章 PASS。Debug CI 38269624480 的核心 13＋瀏覽器五組全部 PASS，HTTP／MCP 各 100 輪同主程序、沒有 native crash buffer 記錄。PR #3 已合併 main，產品 source `f7370a808115017c2722a600856e937dde6a3459`；CI 38271166946 已直接驗證此不可 debug 的固定簽章產品 APK：19 組 PASS、0 FAIL、0 NOT_RUN。HTTP／MCP 各 100 輪 PID 3390；無痕重啟測試後，背景 1／10／30 分鐘（60.5／600.5／1800.5 秒）及回前景 PID 6769、實際新文件／JPEG 像素正確，native crash buffer 為空。SHARP 本版尚未驗，非 V1。[功能與驗收範圍](V0_13_BROWSER_FUNCTIONS.md)。

---

## v0.11.0 — 分頁群組與背景截圖（驗收版／DEVICE 待驗）

SOURCE 已實作雙欄真縮圖／搜尋／群組 UI、保存及 MCP 工具、背景 compositor 有限重試與 API wake lease。完整基準 CI 38166025803 已 12 組 PASS：HTTP/MCP 各 50 輪、接管 20 輪、UI 群組／重啟保存、前景／非目前容器，以及螢幕關閉 1/10/30 分鐘新文件 JPEG。導覽修正版 4c52da9150b2e4c01493b7e303a19c8cdc9804e9 的 Python 37、JS 四組、JVM 37、Android Debug/Release、固定簽章 PASS；CI 38171622490 11 組快速操作回歸 PASS，長測明示 NOT_RUN，保留基準來源。APK 已核對 SHA、固定憑證與內嵌 sourceRevision。v0.10 SHARP 報告只證明該版短時間背景；v0.11 真機更新／UI／OEM 長時間仍待驗，非 V1。詳見 [本輪紀錄](V0_11_TAB_GROUPS_BACKGROUND.md) 與 [驗收 Prompt](V0_11_ACCEPTANCE_PROMPT.md)。

---

## 2026-10-11 — v0.10 裝置回報修正（歷史）

SOURCE 與 CI：UI/文件身分/iframe/原生 input/截圖與簽章管線已改，Python 35 項、JS 四組、Kotlin 24 項、Android Debug/Release 與八組模擬器驗收全部 PASS。HTTP 50 輪沒有主程序重啟；實際 MCP 工作流、四個 UI 畫面與背景 12 秒後藍色新文件 JPEG 已核對。最終 [CI 38149220497](https://github.com/Tsenjii/Abwr-/actions/runs/38149220497)，source `968038fe857f413dc6472c98d22e0c0e07b9e4c1`。

SHARP 真機、鎖屏與長時間穩定性仍待重驗，非 V1。v0.9 私鑰已回收、私密備份並存入永久 Actions Secret；實際 CI 已證明 Secret 來源與憑證一致，cache fallback/臨時匯出已移除。真機同簽章更新與保留資料仍待驗。先讀 [建置紀錄](V0_10_BUILD_RESULT.md) 與 [驗收 Prompt](V0_10_ACCEPTANCE_FIXES.md)；以下歷史結果不代替本版證據。

---

## v0.9.0 — AI 通道與操作可靠性（2026-10-11）

v0.8.0 已合併 main。本輪新增橋接有限重連、BFCache 回復、等待 observer 清理、分頁／網址操作前置條件，以及 HTTP/MCP 獨立控制工作佇列。可編輯文字區支援純文字輸入，隱藏／停用／唯讀欄位會拒絕操作；等待失敗與連線錯誤保留機器可讀代碼。未自動重送已發出的點擊或提交。

Python **30** 項、Kotlin JVM **15** 項、**三組 JavaScript**、Android APK 編譯及上傳全部 CI PASS。**沒有新增 DEVICE PASS，仍非正式 V1。** 完整設計、限制與手機驗收步驟：[v0.9 AI 可靠性紀錄](V0_9_AGENT_RELIABILITY.md)。

完整 CI：[Actions #38127631965](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965)；[下載 APK](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965/artifacts/11697644033)。程式碼 commit `c9447b8b70790cbb647e11f12c0a49b80fd06f5a`，後續文件提交不改變已驗證程式碼。

---

## 最新進度：v0.8.0（2026-10-11）

SOURCE：下載管理、HTTP Auth 提示、Prompt 生命周期、日常外觀及選單；修正關分頁 metadata、Popup 導覽、Port ownership/斷線、跨 Surface 截圖及 HTTP/UI 逾時。

Python 23 項與 JavaScript 兩組本機 PASS。新增 13 項 Kotlin JVM 行為測試，Android CI 已通過。**本輪沒有新的 Device PASS**；既有使用者裝置回報保留在下方。

下載使用同一 Gecko 回應串流、64 KiB buffer、2 個 worker／4 個未完成下載；不輸出登入 Cookie，UI 有開啟/分享/另存，API metadata 路由 `/v1/downloads`。不能自動續傳。詳見 [v0.8 裝置驗收](V0_8_DEVICE_CHECKLIST.md)。

APK 更新風險：新 CI 開始快取 debug keystore 並附 `signature.txt`，仍不能保證匹配過去 runner 的簽章；不要求卸載／清除 App 資料。正式 V1 仍需持久 release signing key。


CI 與 APK：[v0.8 完整建置紀錄](V0_8_BUILD_RESULT.md)。v0.8.0 已經使用者同意合併 [PR #1](https://github.com/Tsenjii/Abwr-/pull/1) 至 main；後續版本請核對最新 HEAD。

---

# Abwr 專案進度 — 2026-10-11

## 已達成的里程碑

**CI PASS — Debug APK 已成功編譯並上傳。**

- Repo：[Tsenjii/Abwr-](https://github.com/Tsenjii/Abwr-)
- 成功 CI：[Build Android APK #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)
- 最後成功編譯的程式碼 Commit：`4b0ad43f34c667b9cc95d30dd86a47c9e5c8ec51`
- Artifact：`Abwr-debug-apk`（已確認存在且未過期；內含 APK）
- CI 步驟：SDK、Gradle、Python、Node、Android build、APK upload 全部 success
- 不代表 Android 真機、Google OAuth、多帳號壓力測試已通過。

## 真機驗收回報（2026-10-11）

使用者回報「都行了」，對應上一輪明確指定的兩項驗收：
1. **Device PASS — App 啟動／GeckoView**：Debug APK 安裝及開啟成功，`https://example.org/` 可以顯示。
2. **Device PASS — Local HTTP /health**：在同一台 Android 手機呼叫 `http://127.0.0.1:8765/health`，取得正常 `ok: true`。

**證據範圍**：使用者回報，未取得 logcat 或影片。Bearer Token 的 `/v1/status`、WebExtension DOM，以及後續 click 指令已收到實測回應（詳見下節）；截圖、MCP、Cookie 隔離、Google OAuth 與背景瀏覽仍未驗證。

### 新增：Bearer Token 與 DOM Agent 通道（使用者 Termux 實測）

使用者提供原始 API 回傳（不收錄憑證）：
- `GET /v1/status` 使用 Bearer Token 回傳 `ready:true`、`activeProfile:G001`、`residentSessions:1`、`extensionPortReady:true`、`viewAttached:true`、`screenshotSurfaceAttached:true`，目前 `proxyMode:direct`。
- `POST /v1/command` with `{"action":"dom","profile":"G001"}` 成功：`ok:true`、`result.title:"Example Domain"`，含多語系正文，並正確取得 `Learn more` 連結到 `https://iana.org/help/example-domains` 及元素 bounding rectangle。
- **Device PASS：Bearer Token API、GeckoView WebExtension Native Messaging、DOM 資料讀取及元素資訊擷取。**
- **新增 click API 通過（2026-10-11）**：使用者在 Termux 執行 G001 `action:click`、CSS selector `a[href*=\"iana.org\"]`，API 已回傳 `result.clicked:true` 和相同 selector；**未附點擊後 URL**，因此只能確認元素點擊已執行，不能標示成功導航。
- **截圖檔案已落地，待驗證格式**：使用者於 2026-10-11 11:00 提供 Termux `ls -lh ~/abwr-test.jpg` 結果，存在 9.2 KB 檔案（`/data/data/com.termux/files/home/abwr-test.jpg`）。系統缺少 `file` 指令，故仍須驗證 JPEG magic bytes `ff d8 ff`、實際可讀取圖片，並避免將錯誤 JSON 誤判為截圖成功。
- **尚未測試**：AI 輸入／捲動、截圖 JPEG 真正下載結果、人工接管、profile 隔離、MCP client 連接（目前直接由 Termux curl 呼叫 HTTP）、Google OAuth、背景截圖及 Proxy。

## CI 排錯軌跡（提供給下一位 AI）
1. [#38077756568](https://github.com/Tsenjii/Abwr-/actions/runs/38077756568)：`android-actions/setup-android@v3` 安裝不存在的 `tools` SDK package。
2. [#38077838078](https://github.com/Tsenjii/Abwr-/actions/runs/38077838078)：GeckoView 157 / AndroidX Core 1.19 要求 API37、AGP >=9.1。
3. [#38078020438](https://github.com/Tsenjii/Abwr-/actions/runs/38078020438)：SDK 套件名稱由 `android-37` 更正為 `android-37.0`，`compileSdk { version = release(37) }`。
4. [#38078117398](https://github.com/Tsenjii/Abwr-/actions/runs/38078117398)：Kotlin 編譯器 2.2 無法讀 2.4 metadata；使用 KGP 2.4.20。
5. [#38078350384](https://github.com/Tsenjii/Abwr-/actions/runs/38078350384)：剩 3 個 Kotlin 錯誤：nullable Throwable、GeckoView 無 `captureFullPage()`、可空 Bitmap。
6. [#38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)：**編譯及 Artifact 上傳全部成功。**

## 功能成熟度

| 功能 | Source | CI | Device |
|---|---|---|---|
| GeckoView Android 瀏覽器 | 已寫 | PASS | **PASS：使用者確認可載入 example.org** |
| contextId Cookie/storage 隔離 | 已寫 | PASS | 未測 |
| profile 建立、切換、持久 metadata | 已寫 | PASS | 未測 |
| HTTP API、token | 已寫 | PASS | **PASS：/health、Bearer Token /v1/status** |
| MCP stdio bridge | 已寫 | PASS + Python 測試 | 未測 |
| DOM／click／type／scroll | 已寫 | PASS + JS 模擬測試 | **DOM PASS；click 回傳 clicked:true（跳轉未驗）；type/scroll 未測** |
| 人工接管、popup | 已寫 | PASS | 未測 |
| 可見畫面截圖 | 已寫 | PASS | **下載檔案存在（9.2 KB）；JPEG 格式/畫面待驗** |
| 全頁截圖 | 尚未完成 | 不適用 | 未測 |
| 背景無畫面截圖 | 尚未完成 | 不適用 | 未測 |
| 原生 Proxy | 尚未完成 | 不適用 | 未測 |
| Google OAuth／CF 挑戰 | 依網站正常流程 | 不適用 | 未測 |
| 100+ Google 登入狀態保持 | 僅 metadata | 不適用 | 未測 |

## 下一步
已通過 Android 真機 App 啟動、網頁載入、/health、Bearer Token /v1/status 與 action=dom。**下一步順序**：確認 click 後網址 → 驗證前景截圖檔案為 JPEG → 在非敏感測試頁面驗證 type/scroll → 人工接管／交還 Agent → profile 隔離與 MCP tool call；之後才測 popup、100 Profile 壓力測試與 Proxy。逐項回填 Device PASS/FAIL。


## 2026-10-11 — v0.3 / v0.3.1 工程更新

**既有 Device PASS（不得改寫）**：使用者本機驗證 Browser/health/Bearer API/DOM、click API 回傳；截圖檔成功下載 9.2 KB（是否完整 JPEG 已被使用者口頭確認，但沒有圖檔上傳）；使用者 AI 回報 iana.org → icann.org 與搜尋輸入提交成功。HTTP 與 MCP 真實測試是兩件事。

### v0.3 已通過 CI
- [GitHub Actions #38108973660](https://github.com/Tsenjii/Abwr-/actions/runs/38108973660)：APK build PASS（是否有 artifact 由 Actions 頁檢查）。
- `POST /v1/command` 的 navigate/goto/open/load/back/forward/reload，相容既有獨立端點。
- `/v1/profiles` 建立、切換、刪除的複數別名，`/v1/profile/*` 舊路徑保留。
- 表單 clear/toggle/keyboard/scrollTo、find CSS/text/role/ariaLabel。
- 後台截圖快取／時間戳：`/v1/screenshot/last`、`/v1/screenshot/meta`；**不是即時背景截圖**。
- 常駐 Android 前景服務由 dataSync 改用 specialUse，顯示通知，提供系統電池設定入口。Android 系統或 OEM 仍可能終止 App，不能保證 24/7。

### v0.3.1 — 私有 VirtualDisplay 實驗（CI/DEVICE 分開）
- 新增 `OffscreenBrowserDisplay.kt`：只渲染 Abwr 自己的 GeckoView，不鏡像使用者手機畫面；建立 private VirtualDisplay / Presentation / ImageReader，App 退背景時轉移相同 GeckoSession。
- App 內「背景截圖實驗模式」開關，**預設關閉**，避免影響已可用版本。
- `/v1/status` 檢查 `experimentalBackgroundRendererActive`、`experimentalBackgroundSurfaceAttached`、`experimentalBackgroundSurfaceError`。
- Android 設備實際渲染、關螢幕、系統省電、低記憶體等需實測。**不要只憑 CI PASS 宣稱背景截圖已修好。**
- 相關工程限制與下一步見 [BACKGROUND_AND_AGENT_ROADMAP.md](BACKGROUND_AND_AGENT_ROADMAP.md)。

### 真機下一輪驗收
1. 保留原本可用 G001 與儲存資料，不清除 App 資料。
2. 先關閉背景截圖實驗模式，驗證前景行為沒有退化。
3. 開啟開關，按 Home，在 Termux 請求 `GET /v1/status`，確認虛擬渲染狀態、錯誤。
4. 在 Abwr 退背景後請求 **`GET /v1/screenshot`**，實際確認 JPEG 且畫面是最新的（用在前景切到不同頁面比對時間戳）；切忌用 `/last` 假裝即時成功。
5. 測 1/10/30 分鐘、鎖屏、系統內「不限制」電池模式，記錄結果；手機被使用者強制停止不能期望系統自動重啟。
6. 逐項驗證 API aliases、不同 profile 的 Cookie/storage、手動接管，Google OAuth 尚無成功證據。


## CI 更新（2026-10-11）
- **v0.3.0 CI PASS**：[Actions #38108973660](https://github.com/Tsenjii/Abwr-/actions/runs/38108973660)。
- **v0.3.1 CI PASS**：[Actions #38109117417](https://github.com/Tsenjii/Abwr-/actions/runs/38109117417)。已確認 APK artifact `Abwr-debug-apk` 上傳成功。
- **仍未真機驗證**：實驗性 VirtualDisplay 是否能在 Activity 停止顯示後產出最新截圖、手機系統能否長期保留服務。既有 v0.2 的前景功能實測結果不代表新版本毫無退化。
- 本次修復詳見 [BACKGROUND_AND_AGENT_ROADMAP.md](BACKGROUND_AND_AGENT_ROADMAP.md)。 


## 2026-10-11 — v0.3.2 無裝置工程優化（CI / Device 狀態待確認）

使用者外出，本輪只做原始碼、測試及 GitHub Android CI；**沒有新真機測試證據**。

1. **容器 LRU 修正**：API 正在操作的 profile 及新開的 profile 不會被閒置容器淘汰邏輯立刻關閉；完成後再回收，避免多工作階段競態。
2. **快取記憶體上限**：最近 JPEG 最多三張、共 4 MiB，並額外記錄最近截圖失敗原因與時間。
3. **背景渲染可觀測**：實驗性 VirtualDisplay 的 `framesReceived`、`lastFrameAgeMs`、錯誤回報；讓 Agent 判斷虛擬顯示器有沒有持續收到影格，不把渲染 Surface 存在等同圖片能拍成功。
4. **導航等待**：`navigate` 可設 `waitForLoad:true`，從 GeckoSession page stop 得知結束，降低盲等；`httpStatusAvailable:false`，不能假裝知道 HTTP 代碼。
5. **人工接管修正**：API back/forward/reload 禁止越過接管，同時保留 App UI 使用者自己的返回／前進／重載。
6. **服務診斷及金鑰輪替**：`/v1/diagnostics` 報 RAM (JVM heap)、存活時間、電池最佳化豁免、背景影格；App 加明確確認後才執行的 API Token 重設，不觸動已存入的容器資訊。
7. **輕量 HTTP server**：修掉每讀入一個標頭 byte 就複製整個 buffer 的 O(n²) 記憶體開銷，拒絕無效 Content-Length 與不支援的 chunked request。
8. **新增 JS WebExtension 實際 mock interaction 測試**、Python API/Controller 靜態守門測試，透過 GitHub Actions 執行。

**剩餘阻塞**：手機真實背景截圖與持續顯示狀態、Android 長時間存活、Google OAuth、Cookie 隔離、MCP 真正工具呼叫、下載上傳仍尚待完成。不要因為 GitHub CI 通過而移除這些未驗收事項。

## v0.3.2 GitHub CI 已完成（2026-10-11）

- **CI PASS**：[GitHub Actions #38110173172](https://github.com/Tsenjii/Abwr-/actions/runs/38110173172)，使用程式碼 Commit `24e1137590cdbe29f16fa59428dc479893001699`。
- SDK / Python tests / WebExtension JS 原始碼及模擬 Native Messaging 互動 / Gradle Android APK assemble / Upload Artifact **全部通過**。
- APK Artifact：`Abwr-debug-apk`（已確認上傳，ZIP size 263,792,229 bytes）。需由 Actions 頁面下載 ZIP 並解壓。
- **仍待 Device 驗證**：Android 真機在退背景、鎖螢幕、開機重啟、長時間運行下的服務存活、VirtualDisplay 擷取是否新鮮、LRU 4+ profile、navigate wait 的實際頁面情境及 Token rotation。
- 尚未修改真機已有的 App 或 Token；使用者可留在舊版，回到裝置時再自行升級驗收。


## v0.4.0 — AI Browser 功能對齊（2026-10-11）

本輪以 Playwright MCP、agent-browser、browser-use 做為**設計參考**，實際寫入：
- WebExtension `snapshot` 產生短小的 Agent-readable 網頁結構與 snapshot ref，如 `e1_2`，可被 `click/type/clear/toggle/select/focus` 以 `target` 使用；失效 ref 需重新取 snapshot。
- 新增 `GET /v1/capabilities` 描述已支援/實驗/未支援的 API 能力，避免 Agent 根據其他瀏覽器工具名猜測。
- Python `bridge/mcp_stdio.py` 擴充至約 30 個 MCP tools（導航、閱讀、表單、截圖、人工接管、獨立 profile、後台診斷），能以 `isError:true` 回傳真正的操作失敗。
- `type` 加 `submit:true`，`wait` 加 DOM 文字等待；保留 `waitForLoad`。
- 新增 Python MCP adapter 測試、Native Messaging VM 實際 mock ref 測試；具體範圍見 [AI_BROWSER_PARITY.md](AI_BROWSER_PARITY.md)。

**待證實**：本輪代碼仍需跑過 Android CI 及手機 MCP 真實 tool calls；背景 GeckoView Surface、Google OAuth、100 Profile 長期登入狀態、檔案下載/上傳等未完成或未驗證。不要從功能列表推導全功能已可用。

## v0.4.0 CI 結果（2026-10-11）

**PASS** — [GitHub Actions #38110756110](https://github.com/Tsenjii/Abwr-/actions/runs/38110756110)：Python protocol/MCP tests、WebExtension 靜態 + Native Messaging mock 互動測試、Android Debug APK 編譯及 Artifact 上傳均通過。

- 編譯來源 Commit：`cb1b26260d72c399ab93b488cfda37fb6585e4be`
- APK Artifact：`Abwr-debug-apk`（ZIP 263,794,242 bytes，尚未過期）。
- **不代表已真機測通**：新版 snapshot refs、MCP OpenCode 連線、多容器登入、背景真實即時截圖、Google OAuth 等仍需獨立驗證。

### 0.4.1 小型安全性修補
元素快照 ref 使用文件載入時間作為編號起點，避免重新導覽後由 `e1_1` 重複開始，降低 Agent 拿舊 ref 點錯新頁面元素的風險。保留每次 snapshot 失效與重取機制。待 CI 與真機驗證。

## v0.4.1 CI 成功（2026-10-11）

- [GitHub Actions #38110892134](https://github.com/Tsenjii/Abwr-/actions/runs/38110892134)：**completed/success**。
- Python protocol/MCP 測試、WebExtension JS Native Messaging mock、Android Debug APK 編譯、Artifact 上傳皆通過。
- 成功編譯來源 Commit：`28198fec7fce6f269192e26bcb36e5dec4169d97`。
- Artifact：`Abwr-debug-apk`，ZIP 大小 263,794,321 bytes，已確認存在且未過期。
- 此版本新增 snapshot ref 跨新文件重置時避免 ID 從零重複的修補；仍需要 Android 真機驗證 Agent 在跨頁面變動時 ref 操作與 MCP tool calling 行為。
- **不代表**背景渲染、長期 Service 生命週期、Google OAuth 已在手機上通過測試。


## v0.5.0 — 日常瀏覽器化（2026-10-11）

- UI 重做：上方 Abwr 品牌與狀態、網址／搜尋、前後頁重載；底部首頁、新分頁、分頁、容器、AI 模式；所有 API Token、DevTools、背景實驗功能均放進選單。
- 真正多分頁 GeckoSession（同容器共用 `contextId`），profile URL 與 active tab metadata 存在 SharedPreferences；最多 12 extra tabs/profile、8 個額外 tab GeckoSession 常駐。
- 書籤和歷史 URL：每容器分開儲存，書籤最多 250、歷史最多 150，支援 UI 以及新 API。
- GeckoView 原生 Enhanced Tracking Protection 預設開啟，提供 UI 與 API 開關；不宣稱完全具備 Brave Shields／完整廣告規則。
- WebExtension read-only `devtools`、`storage_inspect`；Cookie 僅可取得非 HttpOnly 名稱，Storage 疑似敏感憑證值遮蔽。保留密碼/登入資料的使用者控制。
- 本地 MCP 新增分頁、書籤、歷史、DevTools、Storage 相關工具。
- [OpenCode 專用 Agent Skill](../.opencode/skills/abwr-browser/SKILL.md) 指導自主操作與錯誤回復，並保護使用者資料。
- **尚未完成或驗收**：Google OAuth、真正背景即時截圖、完整下載上傳、全功能 DevTools、100 個帳號的持久隔離、App 真機穩定性、系統回收下常駐。不能把 CI PASS 宣稱為 Device PASS。



## v0.5.0 CI 結果（2026-10-11）

- **CI PASS**：[Actions #38111797551](https://github.com/Tsenjii/Abwr-/actions/runs/38111797551) 完成 success，包含 Python MCP/API 測試、JavaScript WebExtension Bridge mock 測試、Android Debug APK 編譯及 Artifact 上傳。
- 來源 Commit：`78b223a13bd15bba5231d125a825f4132251b2d6`
- APK Artifact：`Abwr-debug-apk`（已確認存在，ZIP 263,808,970 bytes，未過期）。
- **DEVICE 未測**：新 UI、多標籤頁切換／還原、ETP 網站登入相容性、Storage／DevTools 輸出、Cookie 隔離、Google OAuth、長時間背景存活。不要把編譯成功寫成真機功能通過。
- **隱私設計**：不會匯出或索取其它 App 的 Cookie；Abwr 內登入狀態只在同一 profile 使用。Web Storage 內容的敏感值是啟發式遮蔽，並非完整 DLP；本機 Agent 應避免轉發網頁資料與憑證。

## v0.5.1 無裝置穩定性優化（2026-10-11）

主要修復與精簡：
- **分頁等待競態**：`PendingNavigation` 持有 GeckoSession 身分，`onPageStart`／`onPageStop` 僅同一 Session 可解開 waiter，不會把別的 tab 完成視為自身成功。
- **每分頁載入狀態**：`tabLoading` 獨立，不再用 profile 單一 Loading 互相覆寫。
- **唯讀狀態**：`status()` 改用 `selectedLoadedSession()` 判斷 bridge，不因 API/畫面刷新意外創建/載入新的 GeckoSession。
- **Popup**：main 與 extra GeckoSession 的 `onNewSession` 共用 popup factory；Google OAuth **仍未真機驗證**。
- **Tab LRU**：新開分頁受 `preserveKey` 保護，並在 in-flight Agent request 結束後再修剪閒置工作階段。
- **人工接管**：Agent 的 tabs/new、tabs/select、tabs/close 受接管鎖保護，App 使用者自己的 UI 能選頁與開關分頁。
- **AI 快速閱讀**：WebExtension `read` 提供 title、主要文字、headings、meta、language、canonical；`find` 依原生 tag 推斷 role，快照與 find 做有限上限掃描，敏感輸入欄位值會遮蔽。
- **API 保護**：Python MCP 連線主機從前綴檢查改成 URL authority 解析，修補 `http://127.0.0.1:8765@other-host` 可能取得 Bearer token 的風險。
- **UI**：細載入指示器、複製網址、Android 分享。
- **自動測試**：read/find、MCP host 拒絕 spoof、Controller 靜態守門。尚待 GitHub CI APK 完整編譯及使用者 Android 真機驗收。

**仍未實測**：Google OAuth、真正背景即時截圖、下載／上傳、長時間 Android Service 存活、多登入容器 Cookie 隔離。避免宣稱這些已解決。



## v0.5.1 CI 驗證已完成（2026-10-11）

- **CI PASS**：[Actions #38113128399](https://github.com/Tsenjii/Abwr-/actions/runs/38113128399) — 已完成 `success`。
- 編譯 Commit：`857727569643d420a56b54fd5138d6f35245df6b`
- Python protocol/MCP、WebExtension JavaScript/Native Messaging mock、Android Debug APK 編譯與 Artifact 上傳均通過。
- 已確認 Artifact：`Abwr-debug-apk`，ZIP 大小 **263,810,675 bytes**，未過期。
- CI 無法替代 Android 真機驗證，尤其是多分頁真實 OAuth popup、背景 render Surface、新版 UI、已登入網站 Cookie 隔離及長時間穩定性。


## v0.6.0 — 全 Repo 稽核與一般瀏覽器功能擴充（2026-10-11）

原始碼：
- 新增 `BrowserSearch.kt`（Google / DuckDuckGo / Brave Search / Bing 搜尋引擎可切換）。
- `MainActivity.kt` 加入 Gecko 原生找字對話框、桌面網站切換、搜尋引擎選單、ACTION_VIEW 外部連結處理、Android 返回鍵及前後頁按鈕狀態。
- `BrowserController.kt` 新增 Finder API、可持久的每容器 desktop UA/viewport（只重新載入可見頁）、`canGoBack/canGoForward` 追蹤並避免切容器時無端啟動主 Session。
- `LocalApiServer.kt` 新增 `/v1/find-in-page`、`/v1/settings/search`、`/v1/settings/desktop`，`/v1/settings` 現回搜尋及桌面設定。
- `BrowserService.kt` 通知可點擊返回 App；Manifest 提供使用者可選的 http/https Android browser intent-filter。
- `bridge/mcp_stdio.py` 增加對應 MCP tools；Agent 無須依賴 OpenCode。新增通用 `AGENTS.md`、`skills/abwr-browser/SKILL.md`、`.agents/skills/abwr-browser/SKILL.md`。
- 依 Repo 主幹完整列出並檢查主要程式、測試、Manifest、文檔模組，缺失／風險見 [完整審查](CODE_AUDIT_2026-10-11.md)。
- 已新增 Python 路由契約和 Kotlin 原始碼守門測試；GitHub Actions 結果需單獨記錄，Device **尚未實測**。

這輪依然**沒有** Google OAuth 成功、Android 背景即時截圖或 100+ 容器登入持久性的真機證據；文件狀態不能替代裝置驗收。


## v0.6.0 CI / APK 驗收完成（2026-10-11）

- **CI PASS**：[GitHub Actions #38113974142](https://github.com/Tsenjii/Abwr-/actions/runs/38113974142) — completed/success。
- 來源 Git Commit：`55fca9514cf56b932069aa7d15ba62025d3ba688`。
- Python Protocol／MCP／新增一般瀏覽器契約測試 **PASS**；JavaScript WebExtension Native Messaging 測試 **PASS**；Android Debug APK Gradle 編譯 **PASS**；Artifact 上傳 **PASS**。
- APK Artifact：`Abwr-debug-apk`，ZIP 大小 **263,819,693 bytes**，已確認未過期。
- 第一次 CI #38113863906 曾因 `SessionFinder.find(...)` 回傳可為 null、Kotlin 要求 nullable-safe access 而失敗；已修正 null 結果明確報錯，再次編譯成功。不要把首次失敗當成仍存在的 Bug。
- **DEVICE 未測**：新桌面模式、頁面尋找實際高亮、多搜尋引擎、從 Android 別的 App 傳入的 Intent、OAuth、真實背景截圖及舊登入容器持久性均待實際 Android 手機操作驗證。


## v0.6.1 — 登入網站相容性補強（2026-10-11）

- 增加 GeckoSession PromptDelegate 接入正常 JavaScript dialog、登入 popup 授權、表單重送及 Android 文件選擇器。
- 增加 PermissionDelegate：網站要求跨網域儲存／定位／通知時，顯示使用者確認；Android 系統定位/相機/麥克風 runtime 權限需本人明確同意。
- 系統瀏覽器開啟入口：Google OAuth 若以 embedded-browser policy 拒絕時可由使用者選用其他瀏覽器，但**不跨 App 匯出登入 cookie**。
- WebExtension `compatibility` 及 MCP `browser_compatibility` 只回報可見挑戰／Google OAuth 拒絕頁訊號，不自動通過 Cloudflare 驗證。
- Android CI / Device 結果須另行記錄。這批程式碼在真機 GitHub/Google/Cloudflare **尚未通過驗證**。
- 詳情及完整真機驗收清單：[LOGIN_COMPATIBILITY.md](LOGIN_COMPATIBILITY.md)。


## v0.6.1 CI 驗證結果（2026-10-11）

- **CI PASS (compile/tests/artifact)**：正式修正來源 Commit `562d7057cec65e7fd5c3946439d41160ce9ad98a`；[GitHub Actions #38115518383](https://github.com/Tsenjii/Abwr-/actions/runs/38115518383)。
- Python protocol/MCP tests：PASS；WebExtension JS Native Messaging tests：PASS；Android Debug APK：PASS；APK Artifact 上傳：PASS。
- 已確認 Artifact `Abwr-debug-apk`（ZIP 大小 263,831,047 bytes）。
- 登入／相容性診斷對 OAuth URL 只顯示 `origin + pathname`，不直接回傳 `?code=` 等一次性授權碼；收窄 Cloudflare 文字判斷以降低誤判。
- **沒有執行真實網站真機登入**。GitHub、Google OAuth、Cloudflare Challenge、網站檔案上傳與權限流程均為 **DEVICE UNTESTED**；原生網站提示不代表一定放行。


## v0.6.2 — 容器隔離與原生防追蹤（2026-10-11）

釐清使用者目標：`G001`、`G002` 分別獨立儲存登入資料且減少跨站追蹤。現有 Gecko contextId 只隔離網站儲存資料，**不產生每容器不同的網頁可見設備指紋**。

- 接入 GeckoRuntimeSettings 原生 `setFingerprintingProtection`，採使用者選擇的全域設定；開啟 Global Privacy Control。
- `privacyReport(profile)`、`GET /v1/privacy`、MCP `browser_privacy` 明確回報 Cookie/storage 隔離及指紋防護的 Runtime-global scope。
- `POST /v1/settings/fingerprint-protection`、MCP `browser_fingerprint_protection`、App 選單切換原生防護；不對外宣稱每個容器具有不同設備指紋。
- 重點限制／後續設計見 [PRIVACY_PROFILES.md](PRIVACY_PROFILES.md)。
- CI / Device：仍需 Android 編譯與真機網站相容性測試。


## v0.6.2 CI 完成（2026-10-11）

- **CI PASS**：[GitHub Actions #38117787228](https://github.com/Tsenjii/Abwr-/actions/runs/38117787228) completed/success。
- Android Debug APK 編譯、Python MCP/Protocol 測試、WebExtension JavaScript 測試及 Artifact 上傳均成功。
- 編譯來源 Git commit：`31bd1fb032d36ef0e131f6b2481b1d740c3684a4`。
- Artifact：`Abwr-debug-apk`，ZIP 263,832,413 bytes，已確認存在、未過期。
- **重要限制**：這份 APK 還未在裝置驗證每容器真實網站指紋差異；事實上目前 profile 只隔離網站儲存，指紋保護套用全 Runtime，`independentPerProfileWebFingerprint=false`。
- 本次沒有做指紋身份仿冒或驗證繞過。未來的全新獨立瀏覽執行環境需另立專案／更大 RAM 預算與實測。


## v0.7.0 穩定性修復與 CI 結果（2026-10-11）

- **CI PASS**：[Actions #38120934637](https://github.com/Tsenjii/Abwr-/actions/runs/38120934637)，Android APK 編譯、Python/MCP 測試、JavaScript 測試及 APK artifact 上傳皆成功。
- 成功編譯 Commit：`0747b9884639e46dbbc6b8b4497fabe5aee97504`；artifact `Abwr-debug-apk`，263,836,455 bytes。
- 修正多容器導覽等待期間的工作階段回收、關閉 Tab/Popup 的待處理 API 取消、Popup URL/標題/載入狀態、跨頁面快取截圖失效。
- 刪除容器時，不允許仍有 Agent 請求或人工接管；已刪除 ID 會持久保留不再重用。GeckoView 157 的 `clearDataForSessionContext` 不回傳完成確認，因此只能說已要求清理，不能宣稱已證實清除完畢。
- 新增 Python MCP 真實 stdio JSON-RPC 協定回歸測試。
- **DEVICE 尚未驗證**：Google／GitHub OAuth、Cloudflare 驗證、Android 背景即時截圖、長時間前景服務、登入狀態持久性。V1 必須通過 [發布標準](V1_RELEASE_GATE.md) 才能宣稱完成。


## v0.7.1 — 實際交接資料與下一階段修復（2026-10-11）

- **新主控檔**：[V1_MASTER_HANDOFF.md](V1_MASTER_HANDOFF.md) 完整記錄使用者需求（Brave/Firefox 日常 UI、任何 AI MCP/HTTP、容器、隱私、背景、OAuth、擴充功能/腳本）、Repo 架構、已實作、未經真機驗證、困難及接手 SOP。
- **下一階段工作**：[V1_NEXT_STAGE.md](V1_NEXT_STAGE.md) 分 P0 背景可靠性、P0 登入與正常驗證流程、P1 日常瀏覽器功能與 UI，有實際驗收標準。
- 背景 Renderer 階段、frame health、限速/明確觸發 Surface 重建及 capturePixels 空白時可操作錯誤（實驗性，**尚未真機確認背景真正 live**）。
- OAuth 系統瀏覽器 fallback 僅允許使用者選其他已安裝瀏覽器，避免選回 Abwr 自身；**不轉移 Cookie、不保證 Google OAuth 成功**。
- 書籤／歷史有搜尋；Popup 可從 Tab UI 正確關閉／返回；正常瀏覽狀態優先顯示網頁標題。
- 增加 `scripts/v1_smoke.py`（本機 Token 不洩漏、可選擇 JPEG 驗證）及契約回歸測試。CI 編譯與 DEVICE 必須分別記錄。

## v0.7.1 CI 成功紀錄（2026-10-11）
- **PASS**：[GitHub Actions #38122221612](https://github.com/Tsenjii/Abwr-/actions/runs/38122221612)。Source commit `dc003a34d62d360a8fa7e5edd8d02e34896ccdf4`。Python／JS／Gradle compile／Artifact upload 全部通過。
- Artifact `Abwr-debug-apk`，ZIP 263,841,016 bytes，已確認存在（未過期）。
- 真機仍未驗證背景即時畫面、OEM 省電下常駐、GitHub／Google OAuth、Cloudflare；因此不能將 V1.0.0 標為完成。
- 完整交接：[V1_MASTER_HANDOFF.md](V1_MASTER_HANDOFF.md)，執行順序：[V1_NEXT_STAGE.md](V1_NEXT_STAGE.md)。
