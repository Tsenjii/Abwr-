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

