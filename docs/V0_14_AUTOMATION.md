# v0.14：手機自動化介面與權限共用

本文件承接使用者提供的 G1–G20 需求，並採用後續修正：**一般瀏覽器操作由 AI 與人類共用權限，人工接管是可選的暫停機制**。Android 系統權限與目標網站本身仍決定實際結果。網站是否登入、提交是否完成，都必須讀取結果驗證。

版本為 0.14.0／code 23，App／API／content bridge／MCP／Python SDK 同版；schema 4，MCP 82 個工具。這是 0.x 候選版，真機證據不由 CI 代替。

## 需求對照與交付範圍

| 需求 | 本版介面與行為 |
|---|---|
| G1 | `pointer_path`（touch move/down/up＋atMs）、`key_sequence`、`geometry`；完整路徑先驗證，換文件／Surface／接管即取消，不重送；回 dispatched、siteCompleted:false、文件與 URL |
| G2／G17 | `/v1/network/observe`；依 profile/tab 選擇真正 Gecko tab；URL、method、status、遮蔽回應頭、時序、frameId、文件識別、redirect 事件；16 個觀察分頁，每個 256 事件 |
| G3／G15 | DOM／find／links 分頁、bounding_box／center／visible／computedRole、開放 shadow root 與 frame 路由；readonly_probe 的 allowlist 查詢須 enabled:true；不接受 JS 原始碼 |
| G4／G18 | `/v1/storage/export` 須 consented:true＋profile/tab/documentId；匯出同源除錯結構，敏感值仍遮蔽；不匯出 Cookie／密碼／權杖原值 |
| G5／G16 | wait 的 selector/text、document_changed、url_changed、load、frame_ready、network_idle、delay；Android Handler 事件泵等待，不用宿主 sleep 等網路；超時回 WAIT_TIMEOUT |
| G6 | `/v1/queues`；page 2 workers／8 pending、control 3／12；pendingBridgeCommands、inFlightProfiles；15 設定檔循序量測，不能宣稱 15 路並行 |
| G7 | 每容器代理以實際 Gecko tab 的 cookieStoreId 路由（首請求用 tabs.get，含新 tab／popup），HTTP／HTTPS／SOCKS5、分欄帳密、runtime 檢查；設定 ACK 不等於連通；本機 15 槽位閘道與內容驗證 probe、冷卻落盤 |
| G8／G14 | `/v1/screenshot/evidence` 原子回 JPEG＋profile/tab/documentId/URL/尺寸/時間戳；既有二進位端點攜帶 Base64 JSON metadata header；舊幀仍 stale:true |
| G9 | takeover 狀態跨程序保存、進入與交還稽核；本人授權的本機 API 可顯式交還；沒有因偵測到一般確認元件而自動鎖住 AI |
| G10／G19 | 診斷版本／sourceRevision／schema／bridge／screenInteractive／wake lease／in-flight／截圖錯誤；SDK 混裝拒絕，MCP capabilities 提供混裝標記；challenge 訊號仍不證明驗證成功 |
| G11 | `/v1/prompts`、`/resolve`：alert／confirm／prompt／HTTP auth／popup／beforeunload／repost／file／網站權限／媒體來源；API 與 UI 回覆同一請求一次，scopeToken 防止回答錯請求 |
| G12 | 下載查詢、接受／取消待確認下載、cancel／remove／分塊 export；檔案 import 回可讀 content URI；API／UI 共用非同步 staging 後交給 Gecko，保留檔名；每檔 32 MiB／合计 64 MiB，最多 8 檔；OS URI 存取權仍須成立 |
| G13 | 原生頁面尋找、read maxChars 與標題、links offset/limit；closed shadow root 保持不透明 |
| G20 | session 保護、觀察與輸入上限、背景 Handler 等待與 Surface 狀態；不重載登入頁來假裝復原，也不保證 force-stop 後執行 |

## HTTP／MCP 對照

| HTTP | MCP | 主要參數 |
|---|---|---|
| POST /v1/command action:pointer_path | browser_pointer_path | steps:[{kind,x,y,atMs}]、imageWidth/Height；2–256 步，0–10000 ms，最後必須釋放 |
| POST /v1/command action:key_sequence | browser_key_sequence | keys 1–128、intervalMs 0–1000，合計 ≤10000 ms；focus 可先單獨完成 |
| POST /v1/command action:geometry | browser_geometry | selector／target、frame／expectedFrameUrl |
| POST /v1/command action:readonly_probe | browser_readonly_probe | enabled:true、query:bounding_box/viewport/visibility/role |
| POST /v1/command action:wait | browser_wait | condition、timeoutMs、previousDocumentId／previousUrl／frame／expectedFrameUrl／idleMs／durationMs |
| POST /v1/network/observe | browser_network_observe | profile、tab、record／clear／afterSeq／limit（1–200） |
| GET /v1/prompts | browser_prompts | X-Browser-Profile |
| POST /v1/prompts/resolve | browser_prompt_resolve | id、scopeToken、profile、tab、decision:accept/dismiss；依類型加 text／帳密／uris／videoId／audioId |
| POST /v1/files/import | browser_file_import | name、dataBase64；≤64 KiB、最多 16 個暫存檔 |
| POST /v1/downloads/action | browser_download_action | profile、id、operation:cancel/remove/export；export 加 offset/maxBytes（≤65536） |
| POST /v1/storage/export | browser_storage_export | consented:true、profile/tab/documentId |
| GET /v1/queues、/v1/audit | browser_queues、browser_audit | audit 可按 profile 篩選；256 筆持久 metadata，無痕不落盤 |
| GET／POST /v1/proxy | browser_proxy_info／browser_proxy_set | profile、server、username、password、enabled |
| GET /v1/screenshot/evidence | browser_screenshot | 同一張圖的 metadata＋imageBase64；MCP 回 text metadata 與 image 兩個 block |

完整 MCP schema：[mcp-schema.json](mcp-schema.json)。HTTP OpenAPI：[openapi.json](openapi.json)。

## 座標與等待

snapshot／geometry 的 rect／bounding_box／center 是 **該 frame 的 CSS viewport**；native pointer 是 Abwr 顯示 Surface 像素。JPEG 路徑須同時給 imageWidth/imageHeight，App 檢查最新截圖尺寸與 documentId 後換算。桌面滑鼠 hover 與 Android touch 不同：DOWN 前的 move 僅定位；不宣稱 dispatch 了滑鼠 hover。

一般未 pinch-zoom 的主文件：CSS center 轉 JPEG 時，x 與 y 都乘 `imageWidth / viewport.width`。CSS 像素為等比例；軟鍵盤會改變可見高度，不能分別用 imageHeight/innerHeight 拉伸 y。使用者 pinch-zoom／視窗改變後須重新取得幾何與新截圖；子 frame 的 CSS 座標不能直接當主 Surface 座標。幾何查詢應在新截圖完成後重讀；IME／顯示大小在路徑內改變回 PAGE_CHANGED，不重送。原生合成 touch 保留 View 的 raw/local 螢幕原點。

`dispatched:true` 只表示已交給引擎。後续請讀 status／DOM／網路事件確認。所有寫入均不自動重送；斷線或超時是結果不確定，不是可安全重做。

DOM selector/text wait 最多 10 秒；原生條件 wait 最多 20 秒。`document_changed`／`url_changed` 以 previous 值比對，不帶舊文件作為拒絕新文件的前置條件。`frame_ready` 需實際 frame id＋expectedFrameUrl；不能猜 index。network_idle 須先 opt-in 記錄，僅指觀察通道内請求的安靜時間，不保證 WebSocket、所有網站背景工作已完成。

## 網路事件與背壓

讀取 afterSeq 表示確認已消費該序號以前的事件，釋放 buffer。超過容量時不阻塞或改寫網站請求；API 回 503 BUSY 並告知遺失，必須明確 clear 再開始。網站主文件的 request 在新的 DOM documentId 尚未產生時為 null，另保留 initiatorDocumentId／Gecko nativeDocumentId；不把上一頁的 DOM ID 當新文件證據。Fetch/XHR 的事件在 request 時固定呼叫所在 frame 的文件 ID，不以後來的新文件替換；未取得識別則為 null。clear／停止會重置觀察中的 pending 集合，network_idle 的範圍為這次記錄窗口。

## 代理的實際邊界

15 槽位是 **最多 15 個設定出口**，不能憑設定數聲稱有 15 個不同 IP。只有 runtime 提供不重複的 cookieStoreId 才接受容器代理；辨識不明或重複時拒絕設定，不能把 Cookie 隔離等同代理識別。新容器初始為 about:blank，先用獨立 opaque context 建立代理政策，再做首次導航，避免首次載入意外直連；Gecko 回報實際 cookieStoreId 後再次檢查唯一性。未知路由或未初始化政策會拒絕請求；配置中沒有直連 failover。既有 cookie/store context 保留，不為設定代理清除帳號資料。

Native anonymous HTTP 沒有容器路由；代理容器請求此 transport 會回 PROXY_SCOPE_UNSUPPORTED，須正常導覽至同源後用 page transport。Gecko 137+ 的 loopback 目標（localhost／127.0.0.1／::1）維持本機直連，不經代理 filter；代理 server 本身仍可是 127.0.0.1:7901–7915。內建 cookies 權限用於取得 cookieStoreId 路由識別，不提供原始 Cookie 匯出。此版未承諾 WebRTC／UDP 的全面出口隔離。代理支援以實際 runtime 回報為準。

閘道只聽 127.0.0.1：7901–7915 是出口槽位，9091 為獨立 Bearer 控制面；8765 為固定 ABWR API，8877 為 fixture。已用埠會回 PORT_CONFLICT，不搶占其他程序。健康 probe 必須真正經過指定槽位，HTTPS 使用系統 CA 驗證。只有 TCP／200 狀態不足以判為健康；請提供預期本文 SHA-256。全池不可用時 GET /slots 回 503 POOL_UNAVAILABLE；GET /slots?waitMs=10000 可等候狀態變更，逾時回 504 QUEUE_TIMEOUT，沒有自動切槽硬闖。

## Termux 安裝與使用

```sh
pkg install python git
# 此路徑僅用 stdlib；requests 可按上層工具需要安裝。
pip install requests
cd Abwr-
# Token 從自己 App 的「AI 連線」取得，勿貼到對話或 Git。
export AI_BROWSER_HOST=http://127.0.0.1:8765
read -rs -p 'ABWR Token: ' AI_BROWSER_TOKEN
export AI_BROWSER_TOKEN
python3 bridge/mcp_stdio.py
```

更新 APK 後，git pull 更新此 repo、重啟 MCP client。設定 stdio command 為 python3，args 為 bridge/mcp_stdio.py 的絕對路徑。`abwr` backend 不讀 executable_path、不載入 Playwright；desktop-chrome 才顯式載入桌面依賴。

```python
from bridge.abwr_shim import AbwrBrowser
browser = AbwrBrowser()
browser.check_versions()
page = browser.context("G001").page("main")
page.goto("http://127.0.0.1:8877/")
page.fill("#name", "owner fixture")
page.on("response", lambda event: print(event["status"]))
page.click("#http-check")
page.wait_for_response(lambda event: event["url"].endswith("/http/echo"))
page.screenshot()
```

Python 產物在 `$HOME/.workbuddy-ai/ledger/`、`shots/`、`state/`，不寫 /sdcard。截圖名稱為 profile_tab_documentId_timestamp.jpg；sidecar URL 移除查詢與 fragment。跨運行配額以出口 bucket 保存，預設 UTC 日，達上限即停止；写入至少 1.2 秒間隔，等待透過 ABWR 原生事件泵。配額不能替代目標服務自己的限制。

啟動 fixture 與 15 組循序量測：

```sh
python3 scripts/termux_acceptance.py --create-test-profiles --profiles 15
# 每次建立 TEST_AUTO_<runId>_<序號>，共用 fixture 出口節流。
# 加 --cleanup-created 才清理本次建立的容器，失敗也保存紀錄並還原原選取容器。
```

閘道設定格式參考 [gateway.example.json](../examples/gateway.example.json)，實際 server／帳密由擁有者在手機填入，檔案 chmod 600。

```sh
read -rs -p 'Gateway Token: ' ABWR_GATEWAY_TOKEN
export ABWR_GATEWAY_TOKEN
python3 bridge/slot_gateway.py --config "$HOME/.workbuddy-ai/gateway.json"
```

SDK `page.probe_proxy(url, expected_sha256)` 在已配置代理的同源文件中發出 no-store GET，核對實際本文 SHA-256 與該文件的 Gecko 回應事件／狀態碼；HTTP／MCP 可組合 proxy info、network observe、page HTTP 使用相同行為。設定 ACK 不當作健康。

Control：GET /slots；POST /slots/enable、/slots/cooldown、/slots/probe。控制面需自己的 Bearer Token，沒有轉送至外網。停止用 Ctrl+C，保留冷卻與失敗次數。

## 錯誤與版本變更

| 代碼 | HTTP | 行為 |
|---|---:|---|
| BAD_ARGUMENTS／INVALID_SELECTOR | 400 | 發出動作前修正參數 |
| PAGE_CHANGED／STALE_REF | 409 | 重讀文件／快照，再決定是否操作 |
| MANUAL_TAKEOVER | 409 | 維持暫停；顯式交還才繼續寫入 |
| BRIDGE_NOT_READY／BRIDGE_DISCONNECTED | 409 | 讀 status，不能重送不確定的寫入 |
| WAIT_TIMEOUT／BRIDGE_TIMEOUT／SCREENSHOT_TIMEOUT | 504 | 未完成；先讀可見效果 |
| BUSY | 503 | retryable:true；降低併發，僅未派送動作可再嘗試 |
| PROMPT_NOT_FOUND | 404 | 原請求已回答／過期，不能再答 |
| SYSTEM_PERMISSION_REQUIRED | 409 | 系統尚未授權；用 Android 原有授權流程 |
| FILE_NOT_READABLE／FILE_TOO_LARGE | 409／413 | 選取 App 已能讀取的 URI；每檔 ≤32 MiB、合计 ≤64 MiB；不提交無效檔案 |
| PROBE_DISABLED／OBSERVATION_DISABLED | 409 | 顯式開啟限定探測／觀察 |
| PROXY_SCOPE_UNSUPPORTED／NOT_IMPLEMENTED | 409／501 | 改用已支援 transport／runtime，不假裝成功 |
| VERSION_MISMATCH／QUOTA_REACHED | SDK | 停止流程，不切槽或重送 |

相對 v0.13，頁面命令失敗現在依代碼回 4xx／5xx；MCP 截圖同時有文字 metadata 與 image，客戶端應按 type 找 image，不能固定讀 content[0]。原路由保留，新增欄位與參數不替換已有容器。

## 驗收紀錄

基線 main v0.13 的固定簽章產品 APK 19 組與 30 分鐘關螢幕 PASS，來源見舊版文件；不當成本版真機證據。

本機 Python 60 項與 JS 六組 PASS。第五輪 ba9de1b／CI 38322690384：Android／Kotlin 58 項、舊核心 13 組、HTTP／MCP 各 100 輪、瀏覽器五組、15 profile 循序 PASS；新原語仍在 pointer／prompt FAIL，檔案步驟尚未跑到；代理首次導航 FAIL 且代理未收到請求。本機追加修正包含 raw/local touch 原點、截圖後重讀幾何、tabs.get 不沿用舊 onCreated store、cookies 權限、非 loopback 的受控代理目標（10.0.2.2）、MCP 條件等待修正、文件 complete 等待、frame 請求身分固定、OpenAPI action-specific schemas、Termux 批次清理與 SDK 控制面版本檢查。檔案 staging、網站權限、HTTP auth、雙代理／TLS／新分頁／失敗不直連仍須最新 head 的 CI 證明，不沿用前輪 PASS。

SHARP 的前景／背景／鎖屏 1／10／30 分鐘及 OAuth／Passkey／DRM 尚未產生本版證據。請用自有／授權測試帳號與正常網站流程；沒有驗證解算、指紋偽造或速率限制繞過。

平台依据：[Mozilla Bug 1854324](https://bugzilla.mozilla.org/show_bug.cgi?id=1854324)、[PanZoomController.java](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/PanZoomController.java)。以上修正仍須本版實際 CI 驗證。
