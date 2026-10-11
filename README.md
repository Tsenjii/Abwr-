## Abwr v0.8.0：日常下載與核心可靠性（2026-10-11）

本輪已實作下載確認、進度／取消、開啟／分享／另存，以及淺色／深色／系統外觀。日常操作放在主選單，Agent 診斷在「工具與連線」。

修正分頁關閉被拒絕卻先刪除 metadata、Native Messaging 斷線等待逾時、Popup 導覽誤寫父分頁、截圖跨 Session/Surface 競態；HTTP 排隊有上限，逾時會取消尚未開始的 UI 工作。

驗證：Python 23 項、Kotlin 13 項、JavaScript 與 Android APK CI 全部 PASS，真機未測。正式 V1 尚未完成。查看 [本輪裝置驗收](docs/V0_8_DEVICE_CHECKLIST.md)、[進度](docs/PROGRESS.md) 及 [完整交接](docs/V1_MASTER_HANDOFF.md)。覆蓋安裝需相同 APK 簽章，不要先卸載有資料的舊 App。


CI 與 APK：[v0.8 完整建置紀錄](docs/V0_8_BUILD_RESULT.md)。本版在 [草稿 PR #1](https://github.com/Tsenjii/Abwr-/pull/1)，尚未合併 main。

---

## Abwr V1 交接與本輪 v0.7.1 修復

**交給下一個 AI：從 [V1 完整主控文件](docs/V1_MASTER_HANDOFF.md) 開始，再讀 [下一階段實作清單](docs/V1_NEXT_STAGE.md) 和 [V1 驗收標準](docs/V1_RELEASE_GATE.md)。** 主控文件完整記錄使用者需求、已完成與待測、背景 504、OAuth/Cloudflare、指紋/插件限制及交接步驟。

v0.7.1 加強：
- 背景私有 GeckoView renderer 現有可診斷的階段、frames age、錯誤提示和**由本人或本機 Agent 明確要求、每 20 秒最多一次**的重建；不把舊圖當 live，也不承諾永久存活。
- 系統瀏覽器登入備援排除 Abwr 自己，避免 OAuth 重新開啟迴圈；不同 App 的 Cookie 仍不共用。
- 書籤／歷史支援搜尋；登入 Popup 關閉邏輯修正；正常瀏覽時標題列優先顯示網站資訊。
- `scripts/v1_smoke.py` 提供 Termux 現場核查工具（不輸出 Token、不儲存截圖），Python/JS/Android CI 結果以 GitHub Actions 為準。

---

## v0.7.0 — V1 穩定性收斂

目前版本仍是 **V1 前的工程驗證版**，並非已在所有 Android 手機通過的正式 V1。

核心改善：長時間導航中的 GeckoSession 不會被 LRU 誤回收；關閉分頁/Popup 立即結束對應失效的 AI 指令；刪容器時保留舊 context ID，不直接重用，避免 Gecko 非同步清理造成同名容器 Cookie 混用；跨分頁截圖快取失效；OAuth Popup URL、標題與 loading 回報獨立；MCP 真實 stdio JSON-RPC 交握回歸測試。

[**正式 V1 發布驗收門檻**](docs/V1_RELEASE_GATE.md) · [API](docs/API.md) · [工程進度](docs/PROGRESS.md)

**保留限制**：背景即時截圖、長時間服務常駐、Google OAuth、Cloudflare 及多帳號登入仍需 Android 真機驗收；單靠 CI 成功不能當作證明。

---

## v0.6.2 — 正確的每容器隱私隔離

Abwr 的每個容器使用獨立 `contextId` 隔離網站 Cookies / Storage，但**不會假稱每個容器有不同的設備指紋**：官方 Gecko 原生指紋防護作用在整個 Runtime 的所有分頁。新增清楚的隱私狀態查詢、全域 Gecko 防指紋開關與 Global Privacy Control。詳見 [容器與指紋隔離說明](docs/PRIVACY_PROFILES.md)。

---


## v0.6.1 — GitHub / Google OAuth / Cloudflare 相容性補強

Abwr 增加正常網站所需的 GeckoView JavaScript 彈窗、彈窗允許、Android 系統檔案選擇器與網站權限詢問；提供登入與 Cloudflare Challenge 的**可見狀態診斷**，幫 AI 分辨驗證頁和完成登入，並允許在內嵌 OAuth 遭政策限制時由本人選擇系統瀏覽器。

**限制：無法保證站方一定放行。** Google 有正式 embedded user-agent 政策；Cloudflare 某些嵌入式瀏覽器不受支援。保持正常 JavaScript、Cookie、Popup 和人工驗證流程，不透過偽裝或規避網站保護。系統瀏覽器的 Cookie 不會自動匯入 Abwr。

[完整登入相容性說明與測試清單](docs/LOGIN_COMPATIBILITY.md)

---

## Abwr v0.6 — 不限 Agent 的日常 Android 瀏覽器

這次以整份 Repo 的程式碼稽核為基礎，新增：
- **GeckoView 原生「在頁面內尋找」**，在長網頁中定位、高亮與前後尋找，不只是 DOM text 查詢。
- **桌面版網站**（每容器設定）：切換 Gecko UA 與 viewport，避免靠注入 JS 偽裝。
- **搜尋引擎選擇**：Google、DuckDuckGo、Brave Search、Bing；網址列查詢與首頁統一套用。
- **Android 外部連結**：提供 http/https VIEW intent filter，使用者可自行選擇讓 Abwr 開啟其他 App 的連結，不會擅自設為預設。
- **導覽操作**：Android 返回鍵先關 Popup，再返回網頁；導覽前後按鈕依實際歷史可用狀態啟用。
- **正式跨 Agent Skill**：`AGENTS.md`、`skills/abwr-browser/SKILL.md`、`.agents/skills/abwr-browser/SKILL.md`；**不限 OpenCode**，任意 MCP 或授權 HTTP Agent 可用，保留舊的 `.opencode/` 入口。
- 設定與尋找新增 HTTP/MCP 工具，便於手機外部 Agent 一致控制。

[完整程式碼稽核](docs/CODE_AUDIT_2026-10-11.md) · [通用 Agent Skill](skills/abwr-browser/SKILL.md) · [API](docs/API.md)

**未宣稱完成**：網站檔案上傳／下載、Google OAuth/Passkey、背景可靠即時截圖及長期 Android 常駐仍有缺口或待真機驗證。這不是完整 Brave/Chrome 複製品。

---

## v0.5.1 穩定性與 AI 閱讀優化

- `browser_read` / `action=read`：文章語意式摘要擷取（標題／段落文字／heading／meta description），限制輸出上限，降低 Agent 操作網頁時的 Token 和 RAM 負擔。
- 修正多標籤頁等待導覽時的分頁競態、背景分頁誤改載入狀態、讀取 `status` 意外啟動 GeckoSession、分頁開啟新視窗未掛 popup handler 等情況。
- 額外分頁 LRU 保護當次新載入的 Session；人工接管時只禁止 Agent 寫入，使用者可繼續按按鈕。
- UI 加入細載入進度條、複製網址與 Android 分享頁面。
- MCP 本機連線網址做嚴格解析，避免把 Token 傳給像 localhost 的惡意 hostname。
- CI 會執行 JS、Python 測試及 Android APK 編譯；背景即時截圖、OAuth、長時間存活仍待真機驗證。


## Abwr v0.5 — 重新設計的日常瀏覽器

**目前新增**：乾淨的瀏覽器網址列與搜尋、上一頁／下一頁／重新整理、真正的 GeckoSession 多標籤頁、每個容器自己的分頁 URL 恢復、書籤和歷史、可開關的 GeckoView 追蹤防護（ETP）、網站級 DevTools 資訊與 Storage 檢視。進階 Agent 設定移入「⋮」選單，首頁不再塞滿 API 字串與開發按鈕。

**我們的特色**：同一 Abwr 網站登入狀態能供本機 AI 透過 HTTP/MCP 使用，容器使用 Gecko contextId 隔離，必要時本人接管；新增 [OpenCode Agent Skill](.opencode/skills/abwr-browser/SKILL.md)（專案內自動發現，亦可複製到個人技能目錄），讓本地 AI 知道怎麼連線、使用及排錯。

此版本對齊 Chrome/Brave 的**部分日常體驗**，不是完整 Chromium/Brave Shields 或 DevTools。Cookie 檢視只回名稱，不可直接導出 HttpOnly 登入憑證；Storage 檢視自動遮蔽疑似 Token。既有 Chrome/Brave 登入資料不會被 Abwr 直接取得。

**驗收狀態**：功能已寫入原始碼，Android CI/真機仍須分別驗證；背景即時截圖、長期常駐、OAuth、完整下載上傳及所有 DevTools Network 事件目前不保證可用。

---

## v0.4 — 對齊 AI Browser 的操作體驗（CI / 真機需區分）

參考 Playwright MCP、agent-browser 的常用操作習慣：新增 Agent 易讀的 `snapshot`，可用單次快照的 `eN_N` 編號再做 click/type；擴充本機 Python stdio MCP 到導覽、表單、等待、截圖、容器與人工接管的完整基本工具群；新增 `GET /v1/capabilities` 機器可讀功能表，避免 AI 猜錯 API。

特色仍是 **Android 原生 GeckoView、多容器隔離、低額外 RAM、本機直接連線、人工接管交還、實驗性背景畫面及快取時間戳**。注意：本版本的 snapshot 是 DOM 輕量摘要而非完整無障礙樹；背景即時截圖、Google OAuth 和下載上傳仍未真機驗證或未實作。

[功能對齊矩陣及 MCP 設定](docs/AI_BROWSER_PARITY.md) · [API 文件](docs/API.md)

---


## v0.3.2 維護進度（待真機驗證）
- Agent 多容器並行安全：修正 LRU 不誤殺 in-flight session；JPEG 快取最多 3 張／總 4 MiB。
- 更方便的 `waitForLoad` 導航、`GET /v1/diagnostics`（RAM 僅 Java heap、電池最佳化、背景影格）。
- 增加使用者確認後才能執行的 API Token 輪替；手動接管不再限制使用者自己的返回鍵。
- Android 離屏背景渲染仍為**預設關閉的實驗功能**，不能將 build 成功宣稱手機背景可保證拍照或永不被系統停止。
- 詳見 [API](docs/API.md) 與 [工程進度](docs/PROGRESS.md)。


# Abwr — Android AI Browser Server

**Kotlin + GeckoView + WebExtension + 本機 HTTP API + MCP 橋接**

專用於受信任的本地 AI Agent 的 Android 瀏覽器服務。與你平常使用的 Chrome／Brave 網站資料分離；外部 Agent 可控制自己的瀏覽器工作階段。你可以打開 App 手動接管瀏覽器、登入自己持有的帳號，再交還 Agent。

## 目前發行狀態

**2026-10-11：Android Debug APK 已於 GitHub Actions 成功編譯並上傳 Artifact。Android 真機功能仍未完成測試。**

- [最新已成功編譯的工作流程 #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)
- 請登入 GitHub → 點連結 → **Artifacts** → **Abwr-debug-apk**，下載 ZIP 並解壓取得 `app-debug.apk`。
- 第一次安裝需在 Android 系統允許所使用的檔案管理器安裝 APK。此為**測試用 Debug 版**，尚未發行正式版。

## v0.3.1 背景能力（實驗）
- 前景服務現在依「由使用者啟動的本機瀏覽器 API」使用 `specialUse`，避免將此用途誤標為 `dataSync`；Android 仍可能因記憶體、使用者強制停止或 OEM 背景政策終止，不能保證永不被殺。
- App 內可選「背景截圖實驗模式」，嘗試用私有 VirtualDisplay 持續渲染 Abwr 網頁；**預設關閉，尚未真機驗證**。
- `/v1/screenshot` = 請求**即時**畫面；`/v1/screenshot/last` = 只拿快取**舊畫面**；`/v1/screenshot/meta` = 檢查快取拍攝時間。不可混淆。
- API 現支援 `/v1/command` 的 `navigate/back/forward/reload` 別名及 `/v1/profiles` CRUD 路徑。
- 請參閱 [背景工程與完整優先級](docs/BACKGROUND_AND_AGENT_ROADMAP.md)。

## 核心功能：原始碼及編譯狀態

| 功能 | 目前狀況 |
|---|---|
| Android GeckoView 瀏覽器 | 程式碼已編譯；待真機驗證 |
| GeckoSession profile contextId（與個人 Chrome/Brave 分開） | 已編譯；隔離效果待真機驗證 |
| 最多 300 個 profile metadata、最多 3 個開啟中的主要 Session | 已編譯；100 帳號尚未壓測 |
| HTTP loopback 服務 `127.0.0.1:8765`、Bearer Token | 已編譯；待真機 API 測試 |
| DOM/find/click/type/scroll/wait 等 WebExtension 工具 | JS 模擬測試通過；待實測 bridge |
| Python MCP stdio adapter | Python 協定測試通過 |
| 螢幕畫面截圖（有可見 GeckoView 時） | 已編譯；待真機驗證 |
| 手動接管及返回 Agent 操作 | 已編譯；待真機驗證 |
| popup 基礎處理 | 已編譯；待真機驗證 |
| 整頁截圖 | **未支援**（請使用可見畫面截圖） |
| 不顯示 App 的背景截圖 | **尚未完成** |
| 自訂 Proxy | **尚未完成**，API 回應不支援 |
| Google OAuth／Cloudflare 自動驗證 | **未驗證**；不承諾可繞過安全檢查 |

## 詳細文件

- [完整專案目標](docs/PROJECT_GOALS.md)
- [最新進度與每次 CI 排錯記錄](docs/PROGRESS.md)
- [AI／開發者交接文件](docs/HANDOFF.md)
- [架構](docs/ARCHITECTURE.md)
- [API 範例](docs/API.md)
- [測試計畫](docs/TEST_PLAN.md)
- [安全說明](docs/SECURITY.md)

## 使用方式（需先安裝 APK 並實測）

開啟 App → **啟動服務** → **複製 API Token**。服務預設僅在 Android 本機 `127.0.0.1:8765` 監聽。

```sh
curl http://127.0.0.1:8765/health
export AI_BROWSER_TOKEN="貼上從 App 複製的金鑰"
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" \
  http://127.0.0.1:8765/v1/status
```

若本機 AI Agent 有 MCP stdio 支援，可以設定 `python3 bridge/mcp_stdio.py`，並透過環境變數提供 `AI_BROWSER_TOKEN`。

**注意**：不同 Android App 對回環網路連線可能有不同限制。若從電腦測試，可透過 ADB `adb forward tcp:8765 tcp:8765`。不可把本機 API 直接暴露到公網。

## 編譯

CI 使用 AGP 9.1.1、Gradle 9.3.1、JDK 17、Android API 37.0、Kotlin Gradle Plugin 2.4.20、GeckoView 157。GitHub Actions 會跑 Python/JS 測試，接著 `gradle :app:assembleDebug --stacktrace` 並上傳 APK。該工作流程已成功完成一次；本專案不包含 Gradle wrapper JAR，使用 CI 提供的 Gradle。

**限制**：可以編譯不等於能穩定登入 Google。Google 可能拒絕內嵌瀏覽器 OAuth；Cloudflare 需要遵守網站提供的正常驗證程序。本專案不提供繞過驗證、安全措施或帳號限制的功能。
