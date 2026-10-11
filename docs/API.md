## v0.8.0 日常下載及錯誤契約

- `GET /v1/downloads`：Bearer 認證，預設 UI 容器，或 `X-Browser-Profile`；回 `profile` 與 `downloads` metadata（id/name/mime/sourceHost/state/bytes/total/createdAtMs/error）。無下載網址、原 Cookie、任意檔案內容或遠端自動批准下載能力。開始/取消/另存目前由本人 UI。
- `/v1/status` 增加 `pendingDownloads`、`screenshotInFlight`、`lastBlockedSiteInteraction`。最後一項僅記先前因來源頁不可見而拒絕的網站提示/權限/下載確認，需要本人回來源頁重試。
- `GET /v1/capabilities` 回 `apiVersion:0.8.0`；`download_manager_local` 是 App UI，檔案上傳 picker 仍由使用者挑檔，沒有任意上傳 API。
- HTTP 同時三個 workers、最多十二筆等待，過載回 `503 {ok:false,code:BUSY,...}`。Agent 可延後重試；UI 工作超過 HTTP deadline 會取消尚未開始的工作/內層等待，已送出的網站副作用不能倒轉。
- 活躍 Port 斷線/替換會令所屬 DOM 指令立即失敗；回覆必須對應相同 Session 與 Port。關分頁被拒絕不得丟 metadata；下載中的 Tab 也會受保護。
- Popup 的 navigate wait/URL/title/loading 依該 Popup Session，導覽不再改寫父頁 URL；人工接管時 Agent 關 Popup 拒絕。
- 截圖串行、最多等待 12 秒，編碼在背景 thread；Page/Session/Surface 變更會失敗，遲到圖片不得寫 cache。`/last` 仍是明確舊圖。

[裝置驗收與簽章注意事項](V0_8_DEVICE_CHECKLIST.md)。SOURCE/Python/JS PASS，Android CI 待查，DEVICE PENDING。

---

## v0.7.1 背景修復與安全核查

- `GET /v1/diagnostics` 新增 `backgroundRendererPhase`（disabled / no_surface / pending / foreground / surface_detached / attached_no_frames_yet / frames_observed / no_recent_frames / error）、`backgroundRecoveryAttempts`、`backgroundRecoveryCooldownMs`。一段時間未收到 frame 不必然等於網頁壞掉（靜態頁可能不重繪），但超過 7 秒仍沒有任何 frame 時，背景即時截圖會回可操作的錯誤。
- `GET /v1/status` 同時回報 `backgroundRendererPhase`。
- `POST /v1/background/recover`：明確要求嘗試重建**本 App 私有** VirtualDisplay，限每 20 秒一次，只在實驗背景模式已啟用且 App 非正常前景時使用。回 `retryScheduled` 不代表真的可取得畫面；應再查 `/v1/diagnostics` 和新的 `/v1/screenshot`。
- stdio MCP 工具 `browser_background_recover` 對應該指令。
- Termux 診斷命令：`python3 scripts/v1_smoke.py`；加 `--screenshot` 只驗證新 JPEG 簽名及 bytes、**不保存網站畫面**。使用者本機需設定 `AI_BROWSER_TOKEN`。

完整交接：[V1_MASTER_HANDOFF.md](V1_MASTER_HANDOFF.md)，下一階段：[V1_NEXT_STAGE.md](V1_NEXT_STAGE.md)。

---

## v0.7.0 穩定性契約

- `GET /v1/status`：新增 `pendingBridgeCommands`、`retiringProfiles`；Popup 開啟時 `activeTab:"popup"`、`url`／`title`／`loading` 代表真正 Popup（當前 GeckoSession），不再把主分頁誤報成登入彈窗。
- `GET /v1/tabs`：Popup 條目包含 `url`、`title`、`loading`。
- `POST /v1/profile/delete` / `POST /v1/profiles/delete`：不能刪除正在操作或人工接管的容器。成功回 `{"removed":"G002","storageClearRequested":true,"idReserved":true,...}` 表示容器已從列表移除，Gecko 已收到清理請求；**沒有清理完成的確認回呼**。舊 ID 永久保留不再重用，以免混入舊 Cookie。
- `POST /v1/profile/create`：建立仍在非同步清理中的同名容器會拒絕。
- `GET /v1/screenshot/last`：導航/切換 Tab/Popup 之後若還沒重新截圖，可能回「沒有快取」。這是刻意避免把另一頁的舊 JPEG 誤認成目前頁面；即時截圖若中途換頁會回錯誤。
- `/v1/command`：若 WebExtension 指令途中 Tab/Popup 關閉，所屬待處理指令會立即失敗，而非一定等到 14 秒超時；正在等待導覽的 GeckoSession 受到 LRU 保護。

[完整 V1 Release Gate](V1_RELEASE_GATE.md)。



## v0.6.1 登入相容性診斷

`POST /v1/command`：
```json
{"action":"compatibility","profile":"G001"}
```
讀取**當前頁面**的 Challenge/OAuth 錯誤可見訊號，回傳可能的 Cloudflare/Turnstile 提示與 `disallowed_useragent` 字樣。這不是自動通過人機驗證，也不包含原始登入 Cookie。對應 MCP 工具：`browser_compatibility`。

這版也加入 Android 內網頁 JS 彈窗、人工允許 Popup、Android 系統檔案挑選、網站權限詢問。Google 嵌入式 OAuth 若遭政策封鎖，可由使用者從選單選擇「在系統瀏覽器開啟」，**系統瀏覽器的登入資料不會自動搬到 Abwr**。

完整相容性、限制及 Device 驗收：[LOGIN_COMPATIBILITY.md](LOGIN_COMPATIBILITY.md)。

---

# Abwr v0.6 新增：標準瀏覽器操作

原本端點仍有效。以下均需 Bearer Token，供**任何**本機 HTTP Agent 使用，不依賴 OpenCode。

| Method | Path | Body / 說明 |
|---|---|---|
| GET | `/v1/settings` | 新增 `searchEngine`、`availableSearchEngines`、當前容器 `desktopMode` |
| POST | `/v1/settings/search` | `{"engine":"google"}`，也可 `duckduckgo`、`brave`、`bing` |
| POST | `/v1/settings/desktop` | `{"profile":"G001","enabled":true}`，切換該容器的桌面 UA／Viewport |
| POST | `/v1/find-in-page` | `{"profile":"G001","query":"學測","backwards":false}`，回 `found,current,total,wrapped,searchString` |
| POST | `/v1/find-in-page/clear` | `{"profile":"G001"}`，清除高亮 |

MCP 透過 `bridge/mcp_stdio.py` 對應：
`browser_find_in_page`、`browser_clear_find`、`browser_settings`、`browser_desktop_mode`、`browser_search_engine`。

**跨 Agent 使用說明**：`skills/abwr-browser/SKILL.md` 為共同規格；`AGENTS.md` 提供專案級上下文；`.agents/skills/...` 供部分 Agent 自動發現，原本的 `.opencode/skills/...` 保留。任何工具如要用 HTTP，必須先取得使用者本機授權 Token。

桌面模式目前按容器儲存；切換會調整此容器已開啟 Session 的 GeckoView UA/viewport，只重新載入**目前可見**的選定頁面，不批量重載其他分頁。

---

## v0.5.1 增量

- `POST /v1/command {"action":"read","profile":"G001","maxChars":9000}`：省 Token 的閱讀器，返回 `title,url,language,description,canonical,headings,text,truncated`。預設文字上限約 9000 字元，最大 18,000 字元。只提取目前頁面、無遠端爬蟲或 PDF 讀取能力；登入頁的內容仍屬私人資料，請僅供授權 Agent 使用。
- `browser_read`：Python stdio MCP 的相同功能。
- `browser_find` 現支援語意角色的原生推斷（`a` 為 link、`button` 為 button）；僅輕量 DOM 近似，不是完整無障礙樹。
- `GET /v1/status`：`loading` 是目前選取分頁的載入狀態，`extensionPortReady` 只查看現有 bridge，不因讀取狀態意外開啟未載入分頁；`pendingNavigations` 方便診斷卡住的頁面。
- `POST /v1/command action=navigate` with `waitForLoad:true`：現在將等待綁定到呼叫時指定的 `GeckoSession`，其他分頁載入完成不會誤結束本次等待。
- Gecko extra tab 現有 popup/OAuth 視窗的 `onNewSession` 處理，仍需要 Android 真機 Google 登入驗證。
- 人工接管後，Agent 不能再偷偷新增、關閉或切換該容器分頁；使用者 UI 可繼續操作。
- MCP 只允許經過完整 URL authority parse 驗證的 `http://127.0.0.1:PORT`／`http://localhost:PORT`，禁止利用 URL userinfo 欺騙本機 Token 傳送目的地。


# Abwr v0.5 日常瀏覽器與 Agent 新增 API

以下新增功能供持有本機 Bearer Token 的 Agent 操作：

| Method | Endpoint | 用途 |
|---|---|---|
| GET | `/v1/tabs` | 列出目前容器的獨立分頁，支援 `X-Browser-Profile` |
| POST | `/v1/tabs/new` | `{"url":"https://example.org/","profile":"G001"}` |
| POST | `/v1/tabs/select` | `{"id":"T1","profile":"G001"}` |
| POST | `/v1/tabs/close` | `{"id":"T1","profile":"G001"}`（主分頁不能關） |
| GET | `/v1/bookmarks` | 書籤 URL 列表，可帶 `X-Browser-Profile` |
| POST | `/v1/bookmarks/add` | `{"url":"https://example.org/","profile":"G001"}` |
| POST | `/v1/bookmarks/remove` | 移除一個指定 URL 書籤 |
| GET | `/v1/history` | 所選容器的最近網址歷史 |
| GET | `/v1/settings` | 追蹤防護狀態 |
| POST | `/v1/settings/tracking` | `{"enabled":true}`，GeckoView ETP DEFAULT/NONE 切換 |

`POST /v1/command` 另新增 `action=devtools`（**當前網站的** script errors、Resource Timing、DOM 數量、Viewport）與 `action=storage_inspect`（同來源 localStorage/sessionStorage；密碼、登入 Token、憑證的可能值遮蔽）。這不是 CDP 全功能 DevTools，也不是所有 HTTP headers／瀏覽器內部資料的出口。 `cookie_metadata` 僅回 Cookie 名稱，無 HttpOnly cookie 值。Agent 使用同一容器瀏覽**已登入**網站，無須匯出登入 Cookie。

同容器中的分頁共用網站 Cookie/storage，但頁面是獨立 GeckoSession；最多 12 個額外分頁 / 容器，同時常駐 extra tab sessions 最多約 8 個；超過時舊 tab 仍有 URL 記錄但頁面可能重新載入。開啟 App 後 tab URL 會恢復，表單的未提交內容未保證持久。

OpenCode Agent Skill：[.opencode/skills/abwr-browser/SKILL.md](../.opencode/skills/abwr-browser/SKILL.md)

---

# Abwr Agent-first v0.4 API 增量

v0.4 新增 `GET /v1/capabilities`（需 Bearer Token），回報 Agent 可以用哪些操作、哪些仍在實驗中或沒有實作。

**快照與元素參照**：
```bash
curl -sS -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"snapshot","profile":"G001","limit":60}' http://127.0.0.1:8765/v1/command

curl -sS -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"click","profile":"G001","target":"e1_2"}' http://127.0.0.1:8765/v1/command
```
其中 `target=e1_2` 僅為**示範**，須使用前一次實際 snapshot 所列的 ref。快照刷新或網頁導覽後請重新取得編號，避免誤點。此 snapshot 是輕量 DOM/角色摘要，**不是完整的 Android 無障礙樹，也不是 Playwright selector**。

`type` 現支援 `submit:true`（透過目標輸入框關聯 form 的 `requestSubmit`；若沒有 form 會報錯），`wait` 可以以 `selector` 或 `text` 等待，最長 10 秒。

**Python stdio MCP**：位於 `bridge/mcp_stdio.py`，將 HTTP action 與多容器、人工接管、錯誤處理整合成約 30 個工具，包含 `browser_snapshot`、`browser_click`（可帶 CSS 或 ref）、`browser_type`、`browser_wait`、`browser_navigate`、`browser_screenshot`、`browser_diagnostics`、`browser_takeover` 等。頁面操作 `ok:false` 現轉為 MCP `isError:true`，讓 AI 能正常決定重試或重新取快照。現階段透過 Python 本機 stdio 連線，不是直接由 Abwr HTTP 端點提供 MCP 協定。

**完整差距／特色**：詳見 [AI_BROWSER_PARITY.md](AI_BROWSER_PARITY.md)。

---

# Abwr 本機 API — v0.4.0

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


## v0.3.2 增量 — Agent 穩定性與診斷

- `GET /v1/diagnostics`：回傳 `uptimeMs`（Controller 存活時間，不保證 Service 從未重啟）、Java heap used/allocated/max bytes（**不含 Gecko Native/整台手機 RAM**）、`batteryOptimizationExempt`、現有 resident profiles、in-flight profiles、背景渲染收幀和最近截圖錯誤。此端點需要 Bearer Token。
- `GET /v1/status`：增加 `inFlightProfiles`、`experimentalBackgroundFramesReceived`、`experimentalBackgroundLastFrameAgeMs`、`lastScreenshotAtMs`、`lastScreenshotError`。
- `POST /v1/navigate` 可設定 `{"url":"https://example.org/","profile":"G001","waitForLoad":true,"timeoutMs":12000}` 等候 GeckoSession 的實際 `onPageStop`，回傳 `navigationCompleted`、`loadSucceeded`、`url`、`title`。允許 timeoutMs 250–14000；逾時回 504。沒有 HTTP response status 資訊，`loadSucceeded` 不代表網站 HTTP 200。
- `POST /v1/command` 的 `navigate`／`goto`／`open`／`load` 也可帶 `waitForLoad:true`，此時完整結果會包在 `{"ok":true,"result":{...}}` 中。
- LRU 保留最多三個無操作中的 GeckoSession；受 API 正在操作的 session 被暫時保護，可能短暫超過三個，完成後才回收。metadata 仍最多 300 個，**不能將 metadata 上限視為 300 個並行網頁**。
- 使用者在「接管」模式時，App 自己的返回／前進／重載可正常操作；Agent 的同類寫入命令仍會回 409。
- App 有「重設 API Token」按鈕，僅在使用者確認後執行。重設不清除容器，但會使舊 Agent Token 失效。若只用本機 Agent，不必每次重設。
- 截圖快取改為最多 3 張、總計不超過 4 MiB，防止不受控佔用。未成功取得 JPEG 不能算背景截圖成功。

```bash
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" http://127.0.0.1:8765/v1/diagnostics
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"navigate","url":"https://example.org","waitForLoad":true,"timeoutMs":12000}' \
  http://127.0.0.1:8765/v1/command
```
