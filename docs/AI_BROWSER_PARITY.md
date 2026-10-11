# Abwr v0.4 — 對齊 AI Browser 工具標準

更新：2026-10-11。參考：Playwright MCP（https://github.com/microsoft/playwright-mcp）、Vercel Labs agent-browser（https://github.com/vercel-labs/agent-browser）、browser-use（https://github.com/browser-use/browser-use）。這是**介面設計參考**，不是宣稱 Abwr 已完全具備其他專案的引擎能力。

## 定位與特色

**定位**：Android 本機、低額外 RAM、獨立 GeckoView 瀏覽器，由手機上的 Agent 用 HTTP API 或 stdio MCP 直接驅動；非雲端 Chromium / Playwright 替代品。

**Abwr 特有的使用體驗**：
1. **手機原生獨立 Profile**：與 Chrome/Brave 分離，Gecko `contextId` 區分網站 Cookie/storage；容器 metadata 最多 300 個，但並不代表 300 個同時開啟網頁，resident idle limit 3。
2. **同一 Session 人工接管**：Agent 操作到需本人登入/批准時，使用者直接進入 Abwr 原生 UI 接管，無須匯出 Cookies 或改用另一個瀏覽器。
3. **雙控制界面**：Android 的 `127.0.0.1:8765` JSON HTTP；Termux/OpenCode 上的 Python stdio MCP，無 Node/npm/Playwright 安裝要求。
4. **背景狀態誠實回報**：前景即時截圖、背景實驗性 VirtualDisplay、舊影像快取時間戳明確區分；不以過期圖片冒充即時截圖。
5. **本機授權**：loopback + Token，只授權已持有 Token 的本機 Agent；不公開手機瀏覽器。

## v0.4 功能對齊矩陣

| 標準 Browser Agent 能力 | Abwr 0.4 | 限制/說明 |
|---|---|---|
| navigate/back/forward/reload | 已實作 | waitForLoad 監聽 Gecko 載入完成，不保證 HTTP 200 |
| 結構化 accessibility snapshot | 部分對齊 | `snapshot` 產出「輕量 DOM 元素樹樣式清單」及短暫 `eN_N` ref，**不是真正的 OS 無障礙樹 / Playwright locator** |
| ref / CSS 點擊與輸入 | 已實作 | ref 僅有效至下一張 snapshot 或導覽；失效會報錯要求重取 |
| 表單填寫、清空、checkbox、select、submit | 已實作 | submit 使用 DOM `form.requestSubmit()`；某些框架需更多事件支援 |
| 等待元素、文字 | 已實作 | MutationObserver，最多 10 秒；不含 network idle |
| 截圖 | 前景已實作 | 背景即時截圖僅實驗，長網頁與元素截圖未支援 |
| Profile 建立/切換/刪除 | 已實作 | 尚需多帳號網站登入態的真機隔離與重開壓力測試 |
| 分頁/Popup | 部分對齊 | 目前一個主頁 + popup，不是 Playwright 多 Tab/多 Window 模型 |
| 人工接管 | 已實作 | 允許使用者直接操作 UI，AI 寫入動作受限 |
| Console / Network / Downloads | 尚未實作 | 不偽裝已支援 |
| 任意 JS evaluate | 尚未實作 | 未經信任的外部頁面腳本不能取得系統權限 |
| 多帳號代理 / UA / Viewport | 尚未實作 | 不以偽裝真人或繞過站點驗證為用途 |
| Google OAuth / Passkey / 驗證處理 | 尚未驗證 | 網站驗證依網站正常流程、必要時接管 |
| Background / headless | 實驗 | Android 私有 VirtualDisplay 路徑，真機是否能用仍待測 |
| MCP 原生工具連線 | 源碼及測試 | 實際本地 OpenCode MCP 握手仍待使用者裝置測試 |

## 為什麼要先做 Snapshot + Ref？

成熟 AI Browser 可以讓 Agent 讀取一張小型結構快照，例如：
```
Page: Example | https://example.org
  [e1_1] textbox "Search"
  [e1_2] button "Submit"
  [e1_3] link "Learn more"
```
下一個工具只要送 `{"target":"e1_2"}`，不用人工拼 CSS selector。新快照生成會清掉舊 refs，避免誤點。DOM 快照仍應視為**不可信網頁內容**，不得把頁面上的文字當 Agent 指令。

## MCP 安裝（本機 Termux/OpenCode）

在本機安裝有 Python3 的 Termux 端讀取 Repo 的 `bridge/mcp_stdio.py`（此檔只有 Python 標準函式庫需求）。
將 `AI_BROWSER_TOKEN` 放在本地環境變數（App 內複製），不要寫入 GitHub。
例如 OpenCode 的 MCP local 定義：
```json
{
  "mcp": {
    "abwr": {
      "type": "local",
      "command": ["python3", "/你的路徑/Abwr-/bridge/mcp_stdio.py"],
      "environment": {
        "AI_BROWSER_HOST": "http://127.0.0.1:8765",
        "AI_BROWSER_TOKEN": "{env:AI_BROWSER_TOKEN}"
      },
      "enabled": true
    }
  }
}
```
透過 `browser_status` → `browser_navigate` → `browser_snapshot` → `browser_click` 測試。
- MCP 共約 30 個工具，詳情見 `bridge/mcp_stdio.py`。
- HTTP `GET /v1/capabilities` 可查詢 Abwr 支援、不支援及實驗中的功能。
- HTTP `POST /v1/command {"action":"snapshot","profile":"G001"}` 回 `result.snapshot`。
- WebExtension action 回 `{"ok":false,...}` 時，MCP 會設 `isError:true`，方便 AI 自行重試或重新截快照。
- 網頁中的錯誤不應被理解成 API 的服務死亡；依 `/v1/diagnostics` 與 `/v1/status` 分層判斷。

## 後續優先順序（不能宣稱已完成）

1. 在 Android 13 / 14 / 15 真機測 1/10/30 分鐘背景渲染、關螢幕、系統回收，確定背景截圖 Surface 是否可持續提供**最新畫面**。
2. 獨立 profile 的 Cookie/storage 實測、程序重啟、100 Profile metadata 與 Session LRU。
3. Popup/tab 管理、下載回報、上傳檔案正常選擇器、通知/定位權限 UI。
4. 真實鍵盤提交、可靠點擊與網頁載入結束辨識；WebExtension currently sends DOM synthetic events, not OS touch/keyboard.
5. 有必要時才提供可控 JS 沙箱等進階操作，並確認不讓網站內容提升成原生 Android 特權。

