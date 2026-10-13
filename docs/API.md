> **2026-10-14 最新已驗證來源：** [完整閒置與產品交接](V0_14_1_IDLE_RESULT.md)。PR #7 已批准合併 main；固定簽章 CI 38441974654 SUCCESS，source `519d82f`，Python 71／JS 六組／JVM 63。Android 13／15 各 22 PASS、保留資料升級與獨立 60／600／1800 秒閒置 PASS。原簽章保留；既有 v0.14.1 release 資產保持原來源。SHARP 真機／15 真實出口 NOT_RUN，非正式 V1。

> **v0.14.1 初次發布紀錄：[完成狀態與下一步](V0_14_1_COMPLETION.md)／[APK 與證據](V0_14_1_BUILD_RESULT.md)。** code 24／schema 4／82 tools；Python 68／JS 六組／JVM 63／固定簽章 Release PASS，Android13/API33 與 Android15/API35 各 22 PASS，含保留資料升級與累計 1/10/30 分鐘鎖屏檢查點。初次發布產品 source `e20ffc97559a2857acb5e5bd64b0d8b1b0f3f153`，CI 38426201545 SUCCESS。AI／UI 共用普通權限；SHARP 真機及 15 真實獨立出口 NOT_RUN，非正式 V1。以下較舊版本紀錄保留為歷史。

> v0.14 原語、HTTP/MCP 對照與錯誤契約：[V0_14_AUTOMATION.md](V0_14_AUTOMATION.md)。[本版 APK／CI 證據](V0_14_BUILD_RESULT.md) · [真機驗收](V0_14_DEVICE_ACCEPTANCE.md)。

# Abwr v0.14 本機 API

App／API／bridge／MCP 均為 **0.14.1**，MCP **82 tools**。只監聽 `http://127.0.0.1:8765`，除 `GET /health` 外皆要求 `Authorization: Bearer $AI_BROWSER_TOKEN`。Token 從自己的 Abwr「設定 → AI 連線」取得，不貼到聊天、日誌或 Git。一般瀏覽器操作由 AI／UI 共用權限；顯式 manual takeover 才暫停 AI 寫入，Android OS 權限仍須實際授予。

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
| read／dom／links／find | read 支援 maxChars／title；DOM／links／find 支援 offset／limit，geometry／visibility／computedRole 與 frame 路由見本版契約 |
| click／focus／clear | selector 或 snapshot target ref |
| type | selector／target、text、可選 submit:true |
| toggle／select | checked／value；停用／唯讀／不可互動目標拒絕 |
| scroll／scrollTo | y，或 direction(up/down)＋pixels 二擇一；scrollTo 用 position(top/bottom) 或目標 |
| key | 原生 Gecko 按鍵，含 Enter／Ctrl+A；可先 focus 目標 |
| keyboard／press | DOM 合成事件，相容舊客戶端，不等於原生 key |
| click_point | viewport x／y；JPEG 座標帶當次 imageWidth／imageHeight，核對尺寸／文件 |
| drag／swipe | viewport fromX／fromY／toX／toY、durationMs 100–5000；active rendered 容器，換文件／Surface／接管即取消 |
| frames | iframe 文件清單；子 frame 操作需 frame＋expectedFrameUrl，不猜 index |
| pointer_path／key_sequence | 有時序的原生 touch 路徑／逐鍵輸入；參數與邊界見本版契約 |
| geometry／readonly_probe | element bounding_box／center／visibility／role／viewport；allowlist probe 需 enabled:true |
| wait | selector/text 最多 10 秒；document_changed／url_changed／load／frame_ready／network_idle／delay 最多 20 秒 |
| devtools | record／clear／captureBodies，明確啟用目前 tab 的 Console／Network；有限本文與敏感欄位遮蔽 |
| inspect_element／storage_inspect | DOM CSS／尺寸／屬性、遮蔽疑似秘密的 Storage |
| compatibility | 可見驗證／OAuth 訊號，不能當已登入證據 |
| cookie_metadata | 可見 Cookie 名稱，不回 HttpOnly Cookie 值 |

navigate 等候呼叫當時的指定 Session 新文件 readyState:complete，回 loadSignal、documentId、loadSucceeded；不等於 HTTP 200，HTTP 狀態需讀 opt-in 網路觀察。fragment 可保留 documentId。所有寫入的 dispatched:true／siteCompleted:false 只表示已派送，必須再讀 documentId／URL／DOM 或網路結果；超時或斷線先觀察，不自行重送 POST／提交。

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

GET /v1/screenshot 回即時 JPEG（12 秒、有限 Surface 重試），核對指定容器／tab／documentId，X-Abwr-Screenshot-Meta 為同次 metadata 的 Base64 JSON。GET /v1/screenshot/evidence 原子回 metadata＋imageBase64，MCP browser_screenshot 回 metadata text 與 image blocks。非 UI 容器在自己的 GeckoDisplay 擷取，不切 UI。首次像素必須正確，不能只看 metadata。

/v1/screenshot/meta 回文件、URL、尺寸、age。/v1/screenshot/last 是舊快取，不是 live。全頁 /v1/screenshot/full 未支援。AI／UI 可透過同一個 scoped prompt broker 回答網站檔案、權限與下載確認；API 提交的 URI 必須是 App 實際可讀。PDF／JPEG／文字另存及 HTML 書籤交換仍提供 Android 選擇器 UI。

## 錯誤與限制

常見：BAD_ARGUMENTS、MANUAL_TAKEOVER、PAGE_CHANGED、STALE_REF、BRIDGE_NOT_READY、WAIT_TIMEOUT、BUSY、NATIVE_INPUT_NOT_READY、SYSTEM_PERMISSION_REQUIRED、SCREENSHOT_TOO_LARGE、HTTP_TIMEOUT、HTTP_CANCELLED、RESPONSE_TOO_LARGE。完整 HTTP 狀態與處置見本版契約。未知欄位／型別在發出操作前拒絕；MCP 保留 isError 與代碼。

普通與私密容器的 HTTP／HTTPS／SOCKS5 代理按 Gecko 原生 tab context 路由；缺固定 getter 的 runtime 對 private scoped proxy 回 NOT_IMPLEMENTED，native anonymous HTTP 不繼承容器代理。任意 evaluate、獨立指紋、完整 CDP、自動下載續傳、網站驗證求解器、Firefox／Chrome 帳戶同步、自建密碼庫仍未提供。Android force-stop 後無法保證繼續；真實 OAuth／Passkey／DRM／OEM 長測仍要 SHARP 證據。

## v0.14 觀察、共用權限與代理端點

| 方法 | 路徑 | 行為 |
|---|---|---|
| POST | `/v1/network/observe` | profile/tab、record/clear/afterSeq/limit；真正 Gecko response、狀態碼／遮蔽 headers／timing，bounded overflow 回 503 BUSY |
| GET／POST | `/v1/prompts`／`/v1/prompts/resolve` | 查詢並回答共享 prompt；id/scopeToken/profile/tab/decision，只有尚未回答且 scope 仍有效的請求能成功 |
| POST | `/v1/files/import` | name/dataBase64，≤64 KiB；回 App 可讀 URI。一般 URI staging 每檔 ≤32 MiB、總計 ≤64 MiB、最多八檔 |
| POST | `/v1/downloads/action` | profile/id/operation:cancel/remove/export，export offset/maxBytes ≤65536；實際 bytes 需再核對 |
| POST | `/v1/storage/export` | consented 必須為布林 true，完整 profile/tab/documentId，可選 expectedUrl；敏感值保持遮蔽並寫 metadata audit |
| GET | `/v1/queues`／`/v1/audit` | bounded queues 與 metadata 稽核；不含 Token／密碼／prompt 回答原值 |
| GET／POST | `/v1/proxy` | profile/server/username/password/enabled；首導航前配置普通容器；設定 ACK 不是健康證據 |

HTTP／MCP 完整對照、參數、錯誤碼、Termux 安裝、15 槽位與健康檢查，見 [V0_14_AUTOMATION.md](V0_14_AUTOMATION.md)、[OpenAPI](openapi.json) 與 [MCP schema](mcp-schema.json)。
