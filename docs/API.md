> v0.14 新增／改變的原語、HTTP/MCP 對照與錯誤契約：[V0_14_AUTOMATION.md](V0_14_AUTOMATION.md)。

# Abwr v0.14 本機 API

App／API／bridge／MCP 均為 **0.14.0**，MCP **82 tools**。只監聽 `http://127.0.0.1:8765`，除 `GET /health` 外皆要求 `Authorization: Bearer $AI_BROWSER_TOKEN`。Token 從 Abwr「設定 → AI 連線」由本人複製，不貼到聊天、日誌或 Git。

可用功能以 `GET /v1/capabilities` 為準；SOURCE、CI、SHARP 結果分開記錄，見 [本版契約與驗收](V0_14_AUTOMATION.md)。

## 容器與控制

GET 使用 `X-Browser-Profile: G001`；POST 可用同一 header 或 JSON profile，衝突回 BAD_ARGUMENTS。未指定時作用於 UI 容器。資源回應包含 profile；main／T1 等分頁 id 只在該容器內有效。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/health`、`/v1/status`、`/v1/diagnostics`、`/v1/capabilities` | 存活／文件 bridge 狀態／程序版本 RAM 背景／能力 |
| GET | `/v1/profiles` | 容器清單 |
| POST | `/v1/profiles`、`/v1/profiles/create` | id 建立容器 |
| POST | `/v1/profiles/switch`、`/v1/profiles/delete` | id 切換／刪除；保留單數 /v1/profile/* 別名 |
| DELETE | `/v1/profiles/{id}` | 拒絕最後一般容器、接管或仍有操作／下載的容器 |
| POST | `/v1/profiles/private` | 建立並切換無痕容器；不保存瀏覽資料與磁碟縮圖 |
| GET | `/v1/tabs`、`/v1/tab-groups` | 容器分頁、群組 |
| POST | `/v1/tabs/new` | profile、url |
| POST | `/v1/tabs/select`、`/v1/tabs/close` | profile、id |
| POST | `/v1/tabs/reopen`、`/v1/tabs/close_popup` | 恢復最近一般分頁／關閉 OAuth 子視窗 |
| POST | `/v1/tab-groups/create/update/move/ungroup` | 群組建立、改名改色、移動、解散，tabs 為字串陣列 |
| POST | `/v1/takeover` | profile、manual:true 接管，false 交還 |
| GET | `/v1/bookmarks`、`/v1/history`、`/v1/downloads` | 按容器的書籤／歷史／下載 |
| POST | `/v1/history/clear` | 清除該容器歷史與返回紀錄；保留登入與書籤 |
| GET | `/v1/extensions`、`/v1/userscripts` | 擴充與腳本狀態；腳本不回傳 code |
| GET | `/v1/settings`、`/v1/privacy` | 設定與隱私真實範圍 |
| POST | `/v1/settings/search` | engine，以 settings.availableSearchEngines 為準 |
| POST | `/v1/settings/desktop` | profile、enabled |
| POST | `/v1/settings/fingerprint-protection` | enabled，全 Runtime，不是每容器獨立指紋 |
| POST | `/v1/find-in-page` | Gecko 原生高亮尋找 |
| POST | `/v1/background/recover` | 明確要求背景 Surface 最佳努力重建，20 秒冷卻 |

既有 contextId 保留；新建／重建同名容器使用新的內部 contextId。`storageClearRequested:true` 不等於 Gecko 已證明清理完成。

## POST /v1/command

必須有 action，可加 profile、tab、expectedUrl、documentId。改文件／分頁拒絕 PAGE_CHANGED；snapshot ref 失效回 STALE_REF，必須重取快照。

| action | 參數與效果 |
|---|---|
| navigate（goto／open／load） | 絕對 HTTP(S) url、waitForLoad、timeoutMs；back／forward／reload 操作目前分頁 |
| snapshot | limit（每 frame 最多 300）、offset、selector、text、role、order、includeHidden；自訂勾選框可回 interactionProxy |
| read／dom／links／find | 精簡正文／DOM／超連結／元素查找 |
| click／focus／clear | selector 或 snapshot target ref |
| type | selector／target、text、可選 submit:true |
| toggle／select | checked／value；停用／唯讀／不可互動目標拒絕 |
| scroll／scrollTo | y，或 direction(up/down)＋pixels 二擇一；scrollTo 用 position(top/bottom) 或目標 |
| key | 原生 Gecko 按鍵，含 Enter／Ctrl+A；可先 focus 目標 |
| keyboard／press | DOM 合成事件，相容舊客戶端，不等於原生 key |
| click_point | viewport x／y；JPEG 座標帶當次 imageWidth／imageHeight，核對尺寸／文件 |
| drag／swipe | viewport fromX／fromY／toX／toY、durationMs 100–5000；active rendered 容器，換文件／Surface／接管即取消 |
| frames | iframe 文件清單；DOM 操作可用 frame 指定 iframe、expectedFrameUrl 核對 |
| wait | selector 或 text、timeoutMs，最多 10 秒 |
| devtools | record／clear／captureBodies，明確啟用目前 tab 的 Console／Network；有限本文與敏感欄位遮蔽 |
| inspect_element／storage_inspect | DOM CSS／尺寸／屬性、遮蔽疑似秘密的 Storage |
| compatibility | 可見驗證／OAuth 訊號，不能當已登入證據 |
| cookie_metadata | 可見 Cookie 名稱，不回 HttpOnly Cookie 值 |

navigate 等候指定 Session 新文件 readyState:complete，回 loadSignal、documentId、loadSucceeded；不等於 HTTP 200。fragment 可保留 documentId。超時不自行重送 POST／提交。

## HTTP 與串流

`POST /v1/http/request`（或 action:http_request），支援 GET／HEAD／POST／PUT／PATCH／DELETE／OPTIONS。

```json
{"profile":"G001","url":"https://example.org/api/test","method":"POST","headers":{"Content-Type":"application/json"},"body":"{\"message\":\"hello\"}","transport":"auto","maxBytes":1000000}
```

- auto：當前文件同來源走 page Fetch，使用該容器 Cookie；其他來源走 native anonymous HTTP。
- page：必須先正常導覽到目標同來源，不借用另一容器 Cookie jar。
- 可帶 tab、documentId、expectedUrl；派送前檢查目前分頁與文件，過期回 PAGE_CHANGED。這些欄位與 profile 一起供 SDK 同源代理健康檢查使用。
- native：不繼承 Cookie；控制 Bearer Token 不轉送網站。Cookie／Host／Content-Length 等由引擎管理。
- headers 是字串物件，body／bodyBase64 二擇一。一般 timeoutMs 上限 20 秒、stream 60 秒、maxBytes 上限 2,000,000；控制 API JSON 本身上限 128 KiB。
- responseEncoding:auto／text／base64，二進位保留原始 bytes；4xx／5xx 保留實際 status／本文。
- followRedirects 預設 false。page 的不透明重導明確回 HTTP_OPAQUE_REDIRECT。
- stream:true 回 streamId，真正按 upstream chunk 回 base64 bytes，不是把完整回應偽切成 tokens。

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/v1/http/streams/{id}/poll` | afterSeq，回 events／nextSeq／done，每次最多 32 事件 |
| GET | `/v1/http/streams/{id}/events` | SSE，Last-Event-ID 續讀事件序號 |
| POST | `/v1/http/streams/{id}/cancel` | 取消，不能回滾已提交的請求 |

最多 4 個未完成 HTTP 工作、12 個保留 stream；完成 90 秒後到期。換文件取消 page stream。

## 截圖與檔案

GET /v1/screenshot 回即時 JPEG（12 秒、有限 Surface 重試），核對指定容器／tab／documentId。非 UI 容器在自己的 GeckoDisplay 擷取，不切 UI。首次像素必須正確，不能只看 metadata。

/v1/screenshot/meta 回文件、URL、尺寸、age。/v1/screenshot/last 是舊快取，不是 live。全頁 /v1/screenshot/full 未支援。網站上傳、下載確認、PDF／JPEG／文字另存及 HTML 書籤交換由本人在 Android 選擇器操作。

## 錯誤與限制

常見：BAD_ARGUMENTS、MANUAL_TAKEOVER、PAGE_CHANGED、STALE_REF、BRIDGE_NOT_READY、WAIT_TIMEOUT、BUSY、HTTP_TIMEOUT、HTTP_CANCELLED、RESPONSE_TOO_LARGE。未知欄位／型別在發出操作前拒絕；MCP 保留 isError 與代碼。

任意 evaluate、每容器代理／獨立指紋、完整 CDP、自動下載續傳、網站驗證求解器、Firefox／Chrome 帳戶同步、自建密碼庫仍未提供。Android force-stop 後無法保證繼續；真實 OAuth／Passkey／DRM／OEM 長測仍要 SHARP 證據。
