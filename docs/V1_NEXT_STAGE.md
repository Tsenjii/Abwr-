> 最新需求以 [v0.14 自動化與權限共用契約](V0_14_AUTOMATION.md) 為準。一般操作由 AI／UI 共用權限；顯式人工接管保留。v0.14 尚在 CI 驗收，SHARP 尚未測；下方旧版進度為歷史。

> 最新 v0.13.0／code 22：日常功能、擴充、腳本、DevTools／HTTP 工作台及連線生命週期修正已實作，Python 37／JS 六組／JVM 55、Debug／Release 與永久簽章通過。main 不可 debug 的固定簽章產品 APK 19 組全部 PASS，含 HTTP／MCP 各 100 輪與 1／10／30 分鐘關螢幕新文件／JPEG；崩潰 buffer 為空。最新來源見 [本版紀錄](V0_13_BROWSER_FUNCTIONS.md)，SHARP 流程見 [驗收 Prompt](V0_13_ACCEPTANCE_PROMPT.md)。下一步是產品覆蓋更新、實際網站登入及手機系統／OEM 背景驗收；下方是早期階段規劃，不再代表插件等 SOURCE 缺口。

## v0.9.0 — AI 通道與操作可靠性（2026-10-11）

v0.8.0 已合併 main。本輪新增橋接有限重連、BFCache 回復、等待 observer 清理、分頁／網址操作前置條件，以及 HTTP/MCP 獨立控制工作佇列。可編輯文字區支援純文字輸入，隱藏／停用／唯讀欄位會拒絕操作；等待失敗與連線錯誤保留機器可讀代碼。未自動重送已發出的點擊或提交。

Python **30** 項、Kotlin JVM **15** 項、**三組 JavaScript**、Android APK 編譯及上傳全部 CI PASS。**沒有新增 DEVICE PASS，仍非正式 V1。** 完整設計、限制與手機驗收步驟：[v0.9 AI 可靠性紀錄](V0_9_AGENT_RELIABILITY.md)。

完整 CI：[Actions #38127631965](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965)；[下載 APK](https://github.com/Tsenjii/Abwr-/actions/runs/38127631965/artifacts/11697644033)。程式碼 commit `c9447b8b70790cbb647e11f12c0a49b80fd06f5a`，後續文件提交不改變已驗證程式碼。

---

## v0.8.0 下一步（2026-10-11）

已實作下載管理、Prompt/HTTP Auth、多 MIME picker、外觀和多項 Session/Port/截圖可靠性修正。先核對最新 CI，依 `V0_8_DEVICE_CHECKLIST.md` 真機測，維持 SOURCE/CI/DEVICE 分層。

剩餘優先：背景 fresh 截圖及鎖屏 1/10/30 分鐘、GitHub/Google/CF 正規登入與容器長期保持、下載與上傳裝置驗收、持久 APK 簽章；之後原生長按／完整權限中心、插件和使用者腳本。下方為歷史階段規劃，下載 SOURCE 缺口已補，尚不能叫正式 V1。


CI 與 APK：[v0.8 完整建置紀錄](V0_8_BUILD_RESULT.md)。v0.8.0 已經使用者同意合併 [PR #1](https://github.com/Tsenjii/Abwr-/pull/1) 至 main；後續版本請核對最新 HEAD。

---

## v0.7.1 已完成的工程部分
- CI PASS：[Actions #38122221612](https://github.com/Tsenjii/Abwr-/actions/runs/38122221612)，source `dc003a34d62d360a8fa7e5edd8d02e34896ccdf4`。
- **背景**：新增 renderer phase、frame health、20 秒限速的本人/本機 Agent 明確請求恢復、沒有 frame 時提前說明不能截圖（真機未證明能產生新 frame）。
- **登入**：外部系統瀏覽器登入選項排除 Abwr，避免自我重開迴圈（Google OAuth 成功與否需真機及站方政策）。
- **日常**：書籤／歷史可搜尋，Popup 關閉不當普通 tab，頁面 title 優先於 AI 偵錯字樣。
- **交接**：完整主控、發布條件、已知困難和可執行的下一階段清單已經建立；後續仍需裝置驗證和下載／插件／使用者腳本實作。

# Abwr V1 下一階段執行計畫（2026-10-11）
**核心：背景可靠性、網站登入、日常瀏覽器完整度；不再把增加 API 名稱當主要進度。**
先讀 docs/V1_MASTER_HANDOFF.md 及 docs/V1_RELEASE_GATE.md。這是工作要求，不代表功能已驗收完成。

## P0-1：背景截圖／前景服務
現況：Android App onStop 後可能失去 GeckoView 可用繪圖 Surface，live screenshot 可能 504。實驗 VirtualDisplay + Presentation 可能建立成功卻沒有產生真正的新畫面；Android/OEM 也可能殺掉前景服務。
工程：
1. diagnostics/status 回報背景渲染階段（關閉、等待接回、顯示已連接但無 frame、有 frame、無 Surface、錯誤），frame age、capturePixels 最近成功／失敗資訊。
2. 只有明確的人為／Agent 本機命令觸發的有限次重建私有背景 Surface；不能無限重啟耗電；不能螢幕錄影／擷取其他 App。
3. screenshot 新舊分離：目前頁面變更則清舊圖；不能把舊快取當 live；沒 Surface 快速回明確錯誤及建議。
4. 背景→前景重新附著原 GeckoSession、保留 Cookie；前景服務 START_STICKY 仍是 best effort，不保證永遠存活。
真機驗收：開頁面拍圖，Home 後 5 秒、1 分、10 分分別查 status/diagnostics/screenshot，改變頁面內容比對 fresh；鎖屏與回前景重測，記錄 HTTP、frame age、耗時和 RAM。

## P0-2：Google／GitHub／Cloudflare 正規登入
現況：GeckoView 已有 PromptDelegate、PermissionDelegate、popup、FilePrompt 基礎，但 Google embedded user agent 有官方政策限制，Cloudflare 可能不支援嵌入式瀏覽器，全部未經完整真機驗證。
工程：
1. 修 Popup 的 URL/Title/Loading、關閉返回原 Tab、頁面 Cookie/Storage 穩定性。
2. 外部登入 fallback **只列其他真正可用的瀏覽器**，避免誤選 Abwr 自己造成循環；提示不同 App Cookie 不能自動共享。
3. 驗證正常網站 JavaScript/Poup/檔案選擇器/網站授權；只以實際登入後可用頁面為成功證據，避免 OAuth code 泄漏。
4. AI 可正常操作登入表單、在需要時讓本人完成 2FA/Passkey/人機驗證，再由 AI 繼續；不得破解 CAPTCHA 或規避身份防護。
真機驗收：GitHub 登入→切分頁／容器→重開 App；一般登入站、Google OAuth popup/政策拒絕、Cloudflare 正常流程逐項記錄登入成功與失敗（不記帳密／token）。

## P1-3：日常瀏覽器 UI 與功能
現況：基本 Tab、網址列、Bookmark、History、Finder、搜尋引擎等已有；UX 和下載、插件、使用者腳本欠完整。
工程順序：
1. 修 Popup 開啟時「關閉分頁」會誤當普通 Tab 的問題；加書籤／歷史搜尋，簡化主要 UI 的選單與狀態。
2. 加真正下載管理與 Android 文件上傳的整合與結果回報；保留檔案在裝置由本人管理。
3. 統一深色主題、網站資訊／權限、長按連結、favicon、書籤分類等。
4. GeckoView WebExtension 外掛：先核對本版 Firefox Add-on API，再做**實際安裝、允許權限、停用與移除**和腳本管理，不能用空按鈕冒充可用。
5. API/MCP 通用、不限定 OpenCode；高頻狀態查詢不觸發額外 GeckoSession 載入。

## 交付／定義
每輪：Git 直接提交真實程式碼、回歸測試、GitHub Actions Debug APK build；記錄 Commit／run／Artifact；更新 Progress/Handoff/Master 文檔。不清除原有使用者帳號資料。
**CI PASS 不是 Android DEVICE PASS**。正式 V1.0.0 要滿足 docs/V1_RELEASE_GATE.md 的 P0 真機驗收；背景長期存活、Google/Cloudflare 放行及真正每容器獨立網頁指紋不能保證。
