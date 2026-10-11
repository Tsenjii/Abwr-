# Abwr 2026-10-11 全 Repo 程式碼稽核紀錄

此輪依 GitHub `git/trees/main?recursive=1` 完整列出版本控制的 32 個檔案，逐項檢查主程式、HTTP 協定、Python MCP bridge、GeckoView WebExtension、Android Manifest/前景服務、CI/測試與文件。這是**靜態審查與編譯驗證**，不是完整 Android 真機／滲透測試。

## 範圍與結果

| 模組 | 靜態審查重點 | 此輪處理或狀態 |
|---|---|---|
| BrowserController.kt | Gecko session/profile/tab 管理、導覽競態、載入狀態、儲存、背景 surface | 原有 session-aware waiter 與 LRU 保留；新增原生 Finder、容器桌面 UA/viewport、Back/Forward 真實狀態；避免只為切換容器無謂重開主分頁 |
| MainActivity.kt | 日常操作入口、網址列、Tab、容器、返回鍵、設定密度 | 新增頁面內尋找、搜尋引擎選擇、桌面模式、硬體返回、導覽可用性、外部 App 打開連結 |
| BrowserSearch.kt | 地址列網址／搜尋判斷及多引擎 URL | 新增集中式解析與 Google/DDG/Brave/Bing 可選項 |
| LocalApiServer.kt | 認證、機器可讀能力、路由、狀態碼、工具一致性 | 新增 find-in-page、desktop/search 設定路由；維持 Bearer Token 與 loopback |
| BrowserService.kt | FGS 通知／生命周期／系統重啟 | 通知標題改為 Abwr、點擊可回到 App；前景服務存活仍無保證 |
| OffscreenBrowserDisplay.kt | ImageReader/VirtualDisplay 與退背景後真畫面 | 本輪保留實驗狀態。**仍未真機驗證**；不能把 cache 誤認真實 live pixels |
| WebExtension content.js | DOM/Agent snapshot/ref/read/find/storage/DevTools/表單 | 目前有輕量閱讀、遮蔽疑似秘密、Snapshot refs；未等同完整 accessibility tree 或 DevTools |
| manifest / Gradle | Browser link intent、Service 宣告、Permissions、版本 | 新增 http/https VIEW+BROWSABLE，可當候選瀏覽器；不強迫設預設 |
| bridge/mcp_stdio.py | 通用 Tool Calls、Host 解析、資料安全 | MCP 不依賴 OpenCode；增加原生尋找、桌面模式、搜尋設定等能力 |
| tests | Python contract、JS Native Messaging mock、Android 編譯 | 新增 API 路由及 Controller 合約驗證；仍需真機 UI/E2E |
| skills / AGENTS.md | 其他 AI 的接入便利性 | 新增通用 `skills/abwr-browser/SKILL.md`、`.agents/skills/...`、`AGENTS.md`，舊 OpenCode 專用保留 |
| docs / README / CHANGELOG | 真實完成範圍、使用方法、交接 | 新功能同步；區分 SOURCE / CI / DEVICE |

## 發現的工程風險（尚未完全解決）

**P0 / 高：**
1. 實驗性 Android VirtualDisplay 是否能在 App 不可見、鎖螢幕、Android Doze/記憶體緊張下持續產生最新畫面，需要真機驗證，CI 無法回答。
2. 目前沒有完整網站授權對話框（通知／定位／相機／音訊）、下載與檔案選擇器支援，會影響日常瀏覽器與 Google OAuth。
3. Google 登入、容器 Cookie 長期保存與隔離、密碼自動填入尚無成功驗收。
4. Session URL/metadata 的持久保存不等於頁面表單／SPA 當下狀態完整復原。

**P1 / 中：**
1. 瀏覽器多分頁目前是最多 12 extra tabs/profile、最多 8 resident extra sessions，過量時會重載舊分頁；需要真機記憶體壓測與 LRU 保護。
2. 背景渲染是可選模式，無法保證 Android OEM 永不殺進程。
3. ETP 為 GeckoView 原生追蹤防護，不等於 Brave Shields 全部自訂廣告封鎖規則。
4. 網頁級 DevTools 只讀 DOM / resource timing / JS 錯誤，沒有完整 CDP/Network 面板。
5. MCP 和 HTTP 暴露的是**整個 Abwr 內已授權容器的操作能力**。Token 必須不外洩；站點內容仍屬不可信資料。

**P2 / 日常體驗：**
1. 書籤／歷史 UI 尚未支援模糊搜尋／資料夾／同步。
2. 尚沒有完善深色模式、頁面權限圖示、下載進度、網站 favicon、長按連結選單。
3. Find-in-page 使用 GeckoFinder 真實定位高亮；大頁面應真機測效能。
4. Desktop view 是每個 Abwr profile 的設定，不是每個網站獨立設定，已清楚暴露於 API。
5. 外部 Intent 目前僅支援安全處理 `http`/`https`，不宣稱通用 URI scheme deep-link 支援。

## 版本驗證

- SOURCE：本輪新增程式／測試與文檔。
- CI：請以 v0.6 對應 Actions run 確認最後結果。
- DEVICE：**沒有進行新一輪手機實測**，切勿把「APK 可編譯」寫成所有操作成功。

後續優先順序應是原生 GeckoView 網站權限處理與下載／檔案選擇器，再做全功能日常瀏覽器與 AI 操作驗收。不要用「繞過網站驗證」代替正常登入流程。


## 已驗證的 CI 結果

- 修復 Nullable FinderResult 編譯失敗後，[Actions #38113974142](https://github.com/Tsenjii/Abwr-/actions/runs/38113974142) **PASS**，Android Debug APK 上傳。
- CI 測試可以驗證 JavaScript / Python 契約和 Kotlin 編譯，但**未**執行 Android instrumentation UI/E2E。這份稽核不等於真機性能、OAuth 或背景常駐驗收通過。
