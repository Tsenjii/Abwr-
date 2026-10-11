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

