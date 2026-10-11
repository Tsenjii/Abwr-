# Abwr 本機 API — v0.3.1

**用途**：Android 上的 GeckoView 瀏覽器控制。所有請求預設只監聽 `127.0.0.1:8765`，請由同一台裝置內信任的 Agent 呼叫。

驗證：除 `GET /health`，其他路徑都要 `Authorization: Bearer $AI_BROWSER_TOKEN`。Token 在 Abwr App 內由使用者複製；**不要提交到 Git / 傳進聊天或日誌**。

## 基礎
| Method | Path | 說明 |
|---|---|---|
| GET | `/health` | 服務存活，無需 token |
| GET | `/v1/status` | 目前 profile、WebExtension 連線、URL、title、loading、截圖 surface 狀態 |
| GET | `/v1/profiles` | profile 列表 |
| POST | `/v1/profiles` | 建立，body `{"id":"G002"}` |
| POST | `/v1/profiles/create` | 建立別名 |
| POST | `/v1/profiles/switch` | body `{"id":"G002"}` |
| POST | `/v1/profiles/delete` | body `{"id":"G002"}`；會清除該容器網站資料 |
| POST | `/v1/profile/create`, `/v1/profile/switch`, `/v1/profile/delete` | 舊版單數路徑，仍保留 |
| POST | `/v1/navigate` | body `{"url":"https://example.org/","profile":"G001"}` |
| POST | `/v1/back`, `/v1/forward`, `/v1/reload` | body 可選 profile |
| POST | `/v1/takeover` | body `{"profile":"G001","manual":true}`；改為 false 交還 Agent |
| GET | `/v1/tabs` | 主頁及是否有 popup 的狀態 |
| POST | `/v1/tabs/close_popup` | 關閉指定 profile 彈窗 |

**注意**：沒有 `/v1/profiles/rename`、新分頁任意切換、檔案上傳下載 API。改容器 ID 涉及原始 contextId 與 Cookie/storage 清理，不能直接假裝已支援。

## 統一指令入口 `POST /v1/command`

所有操作都可以指定 profile ID（預設使用 UI 選取的當前 profile）。

**瀏覽器導覽：** `navigate`（`goto` / `open` / `load` 同義）、`back`、`forward`、`reload`。例如：

```bash
curl -sS -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"navigate","url":"https://example.org/","profile":"G001"}' \
  http://127.0.0.1:8765/v1/command
```

導覽回傳 `navigating` 代表**已發出請求**，不是 HTTP 200 或載入完成的保證。用 `GET /v1/status` 的 `loading`、`url` 與 action `status`／`wait` 進一步確認。

**網頁 DOM 及表單：**
- `dom`：`{"action":"dom","profile":"G001","limit":30}`
- `links`：取得超連結清單
- `find`：`{"action":"find","selector":"input","text":"搜尋","role":"searchbox","ariaLabel":"搜尋"}`，篩選鍵可選；以 CSS 為基礎，回傳最多 100 個元素
- `click`：`{"action":"click","selector":"button","profile":"G001"}`
- `type`：`{"action":"type","selector":"input","text":"測試","profile":"G001"}`
- `clear`：`{"action":"clear","selector":"input","profile":"G001"}`
- `toggle`：`{"action":"toggle","selector":"input[type=checkbox]","checked":true}`
- `select`：`{"action":"select","selector":"select","value":"one"}`
- `scroll`：`{"action":"scroll","y":550}`
- `scrollTo`：`{"action":"scrollTo","position":"bottom"}` 或 `{"action":"scrollTo","selector":"#footer"}`
- `keyboard`：`{"action":"keyboard","key":"Enter"}`，僅合成 DOM KeyboardEvent，**不是硬體鍵盤**；不保證觸發網站原生提交／瀏覽器快捷鍵
- `focus`、`press`：僅 DOM 層操作
- `wait`：`{"action":"wait","selector":"#app","timeoutMs":5000}`，目前只支援 selector
- `status`：回報頁面 JS 的 URL、title、readyState
- `cookie_metadata`：只提供可見 Cookie 名稱，**不回傳 Cookie 值**。

**限制**：不支援 `evaluate` 任意 JS、`upload`、真正拖曳滑鼠、DevTools Network/Console 監控、`waitForNavigation`、自動解決網站驗證。

## 截圖
| Method | Path | 說明 |
|---|---|---|
| GET | `/v1/screenshot` | 嘗試取得即時 JPEG；需要有效 GeckoView 渲染 Surface |
| GET | `/v1/screenshot/last` | 取最後一次成功取得的**舊 JPEG**，可能過期；無快取回 409 |
| GET | `/v1/screenshot/meta` | 取 `cachedAvailable`、`capturedAtMs`、`ageMs`、`stale:true` 等快取資訊 |
| GET | `/v1/screenshot/full` | 尚未實作 |

可加 `X-Browser-Profile: G001` 指定截圖容器；目前只有真正掛在渲染 surface 的當前容器能即時截圖。

v0.3.1 新增**可選實驗性**私有 VirtualDisplay 渲染。App 的「背景截圖實驗模式」預設為關，切到背景才嘗試建立。請用 `GET /v1/status` 的 `experimentalBackgroundRendererActive`、`experimentalBackgroundSurfaceAttached`、`experimentalBackgroundSurfaceError` 檢查狀態；**CI 成功並不代表手機背景即時截圖一定成功**。

## 結果與錯誤
- `/v1/command` WebExtension 動作成功時一般回 `{"id":"...","ok":true,"result":{...}}`；失敗可以由結果中 `ok:false` 或 HTTP 狀態回報。
- 導覽動作可能回 `{"ok":true,"result":{...}}`，不包含 WebExtension 的 UUID。
- HTTP API 錯誤會有 `{"ok":false,"code":"BAD_REQUEST|UNAUTHORIZED|...","message":"...","error":"...","hint":"..."}`（舊版 `error` 欄位保留）。
- `POST /v1/proxy` 仍是 501，單容器 Proxy 尚未實作。

## 基本測試
```bash
curl http://127.0.0.1:8765/health
curl -sS -H "Authorization: Bearer $AI_BROWSER_TOKEN" http://127.0.0.1:8765/v1/status
curl -sS -H "Authorization: Bearer $AI_BROWSER_TOKEN" http://127.0.0.1:8765/v1/screenshot/meta
```

本 API 僅供擁有者授權的本地自動化使用。網站內容不可信；遇到網站驗證或登入要求時，應循網站提供的正常流程處理。
