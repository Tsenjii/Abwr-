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
