# Abwr v0.9.0 → V1 本地 AI 驗收 Prompt

更新：2026-10-11。把下方「給本地 AI 的完整指令」全部交給可操作你手機的本地 AI。這是一份驗收指令，**不是已執行的驗收報告**。

建議順序：先驗收 v0.9.0；有阻塞就修 0.9.x 並重驗；正式 V1 以 [V1_RELEASE_GATE.md](V1_RELEASE_GATE.md) 的 P0 實機證據及持久發行簽章為條件。

## 給本地 AI 的完整指令

你現在是 Abwr 的驗收工程師。請實際執行下列工作，產出可以交回開發 AI 修正的證據、問題清單和建議，不要只整理文件或說明可以怎麼測。

目標是確認 Abwr 是否能作為穩定的日常 Android 瀏覽器，也能讓不同 AI 透過本機 HTTP／stdio MCP 穩定操作。**這次是固定版本的驗收任務**：可在獨立驗收工作目錄新增 fixture、測試 runner 和報告；不要改 Abwr 原始碼、版號、main 或推送修正。先保留失敗證據，讓後續修正版另行驗收。本指令優先於交接文件中「立刻修復／推送」的開發流程。

### 1. 鎖定版本與環境

- Repo：https://github.com/Tsenjii/Abwr- 。有現有 checkout 就先記錄 git status，保留使用者未提交的工作；沒有才 clone。取得 main 的最新狀態，另用乾淨 checkout／worktree 測試，不 reset 或覆蓋他人修改。
- 本輪指定基準：app `0.9.0`／versionCode `18`；已通過完整 CI 的程式碼 commit `c9447b8b70790cbb647e11f12c0a49b80fd06f5a`。
- CI：https://github.com/Tsenjii/Abwr-/actions/runs/38127631965 。APK：https://github.com/Tsenjii/Abwr-/actions/runs/38127631965/artifacts/11697644033 。ZIP 包含 APK 及 signature.txt。
- APK 公開簽章憑證 SHA-256：`c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`。ZIP digest 是 `sha256:91db843a06bb410a20bd74075c9189e1fdf50b57533a96067f88c0f1e95be494`，兩者用途不同。
- 記錄 checkout HEAD、實際安裝 versionName/versionCode、APK SHA-256、簽章指紋、來源／CI run。文件比程式碼新可接受，但要確認 app、bridge、tests、build 設定沒有差異；不能拿新 source 測舊 APK，混成同一結果。
- main 如果已有後續功能修正，先記錄差異；本輪維持上述基準。後續版本的驗收另列，不把兩個版本混合通過。
- 記錄手機型號、Android/API level、RAM、電池／省電狀態、Abwr 電池最佳化豁免狀態、網路、本地 AI／MCP 客戶端名稱與版本、Python／Node／Java／Gradle／Android SDK 版本及可用工具。裝置序號與帳號識別資訊遮蔽。
- 真機、模擬器、mock 結果分開。V1 的 DEVICE 門檻要用真實 Android 裝置，模擬器只能補充。

先讀以下文件，衝突時以指定版本的實際程式碼／能力表核對；舊版段落不是最新版能力：

1. AGENTS.md
2. docs/V0_9_AGENT_RELIABILITY.md
3. docs/V1_RELEASE_GATE.md、docs/V0_8_DEVICE_CHECKLIST.md
4. docs/V1_MASTER_HANDOFF.md、docs/V1_NEXT_STAGE.md、docs/PROGRESS.md
5. docs/API.md、docs/LOGIN_COMPATIBILITY.md、docs/PRIVACY_PROFILES.md
6. skills/abwr-browser/SKILL.md、bridge/mcp_stdio.py、.github/workflows/build-apk.yml

### 2. 保留資料並建立連線

- 不卸載、不執行 pm clear、不清除舊容器、Cookie、歷史、書籤或下載。覆蓋安裝前核對舊／新憑證；不符就保留舊 App，將指定 APK 安裝驗收列為 BLOCKED。可另用空白測試裝置；不能把舊版結果報成 v0.9 PASS。
- Token 由使用者在手機本機私下提供給 `AI_BROWSER_TOKEN` 環境變數；不要要求貼進聊天，不使用 shell trace，不把 Token、密碼、Cookie 值、OAuth code 或完整登入 callback URL 寫入報告／日誌／Git。
- 同機 Termux：`AI_BROWSER_HOST=http://127.0.0.1:8765`。
- 電腦上的 AI 可在已授權 USB ADB 連線使用 `adb -s DEVICE_SERIAL forward --no-rebind tcp:18765 tcp:8765`，設定 `AI_BROWSER_HOST=http://127.0.0.1:18765`。只使用 loopback／已授權安全轉送；核對實際監聽位址，不能將 API 開放到 0.0.0.0 或公網。DEVICE_SERIAL 用本機實際裝置代入；若埠已被其他工作佔用，另選空閒埠，不覆蓋原轉送。
- MCP 啟動：`python3 bridge/mcp_stdio.py`，經 stdio 客戶端執行 initialize → initialized 通知 → tools/list → tools/call。從實際 tools/list 取得 schema，不猜工具或參數。
- 先查 health、capabilities、status、diagnostics、profiles、tabs。記錄使用者原容器／分頁和設定；只記無敏感內容的基線摘要。
- 建立這輪專用且不重複的測試容器，例如 `QA_1011_01` 至 `QA_1011_10`；ID 最多 31 字元。所有會修改網站資料、表單、Cookie 或刪除容器的測試只在這些容器操作。保留測試資料供重現，不默默刪掉。
- 缺手機權限、APK、Token、ADB 或登入帳號時，列出 BLOCKED 的精確原因與最小解鎖步驟，繼續不依賴它的測試。帳號、2FA、Passkey、CAPTCHA 由本人完成；不要擅自改帳號安全設定或對外發訊息／提交真實交易。
- 原容器若有未保存的工作，先讓本人保存，再測程序重建；未能保存就將重建測試列 BLOCKED。測完恢復原 UI 容器／分頁及外觀、搜尋、隱私、背景實驗設定；原本人工接管的容器仍保持接管。只移除本輪新建的 ADB 轉送，不使用 remove-all 清掉其他工作。

### 3. 建立可重現的測試頁

在獨立驗收目錄建立小型、可重跑的受控 fixture，讓它能在 Abwr 開啟。服務只監聽本機；電腦 fixture 可經已授權 ADB reverse 轉送到手機。記錄實際 origin／路由；若 HTTP 或安全 origin 受限，使用可信 HTTPS 或已授權測試站，不修改 APK／關閉 TLS 驗證來讓測試通過。

fixture 至少包含：

- A／B 兩頁，有不同且明顯的頁名、run ID、可更新的大字計數器、導覽／返回連結、長文章與頁尾。
- 普通 input／textarea／純文字 contenteditable、checkbox、radio、select、可驗證的表單；輸入與事件計數呈現在頁面，提交只送到測試服務。
- readonly、disabled、aria-disabled、display:none、visibility:hidden 欄位；CSS 覆蓋／不可見的按鈕。記錄實際 guard 範圍，不把仍有 layout rect 的 hidden 欄位漏掉。
- SPA pushState／hash 變更按鈕、延遲新增元素、永不出現的 selector、Popup 及 Popup 子頁；頁面顯示 pageshow.persisted，辨識 BFCache 是否真的命中。
- 測試登入／登出頁：只用無價值的假帳號與 fixture session；用登入後頁面驗證 Cookie 隔離與保持，不匯出真實網站憑證。
- 一般、UTF-8 檔名、大檔／慢速、截斷回應的下載；可在登入 fixture 取得的附件；多 MIME／多檔上傳頁，回傳選定測試檔案的名稱、大小和 digest。
- 表單提交／click 使用 run ID 與計數器，能從伺服器端證實只執行一次。fixture 原始碼、測試檔及啟動方式要交付。

先確認 fixture 本身可用。fixture／ADB／網路故障列為測試環境問題，不能直接歸因 Abwr。沒有任意 JS evaluate API；透過頁面按鈕和現有工具操作，不假造 unsupported API。

### 4. 原有自動回歸與建置

在固定 source 的乾淨工作目錄執行，保留 exit code、測試數、耗時、日誌路徑及失敗摘要：

```bash
python3 -m unittest discover -s tests -v
node tests/browser_content_mock_test.js
node tests/bridge_behavior_test.js
node tests/bridge_lifecycle_test.js
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

- 本輪既有 CI 基準是 Python 30、Kotlin JVM 15、JS 三組及 Android APK 全部 PASS；這是歷史 CI 證據，不能當作你的本地重跑或 DEVICE 結果。
- Gradle／SDK／debug keystore 缺失時按 workflow 核對環境；不要動既有私鑰或要求匯出它。不能本地建置就列 BLOCKED 並引用精確 CI，不寫 LOCAL PASS。
- 本地生成的 debug APK 簽章可能不同；不要為了換成自己編譯的 APK 而卸載手機現有 App。
- 在目前被測手機上執行 `python3 scripts/v1_smoke.py` 和 `python3 scripts/v1_smoke.py --screenshot`。此 smoke 只初查 API／JPEG bytes；不能代替下方畫面內容、登入或長測。

### 5. 逐項真機驗收

每一個編號都要有結果，不能省略。先跑正常工作流，再跑故障／拒絕情境；後者在「明確拒絕且沒有錯誤副作用」時可以 PASS。普通寫入若 timeout／斷線，先讀取實際結果，不盲目重送。

#### A. 啟動、認證與狀態

- A01：20 次 App 開啟／關閉 Activity／重新開啟，檢查無崩潰、ANR、資料異常，區分熱啟動與真正程序重建；只操作 Abwr，不終止其他 App。
- A02：health 可無 Token；其餘列出的受保護 API 無 Token／錯 Token 必須拒絕。確認正確 Token 有效，報告不洩密。
- A03：查非 UI 容器的 status：profile 是被查容器，activeProfile 仍是 UI 容器；URL、title、activeTab、manualMode 屬於查詢容器。查詢不能切 UI、建立新 Session 或偷偷載入網頁；非 UI 容器不誤稱持有當前 Surface。
- A04：idle 與 page wait 進行中各查 status／diagnostics 20 次，記錄成功率、median／p95／最大耗時及 residentSessions；檢查不是被 wait 的完整 10 秒期限阻塞。可將 control p95 ≤ 2 秒列為本輪效能目標，超過則報效能問題，不能事後改標準掩飾。

#### B. 通用 MCP 與 50 輪 AI 工作流

- B01：真實 MCP 客戶端完成交握、工具 discovery、ping；工具回覆可依 ID 正確配對，stdout 只有完整 JSON-RPC，不混日誌。記錄協定版本；v0.9 支援 legacy 2024-11-05／2025-03-26，若新客戶端不能協商就報相容性缺口。
- B02：至少 50 輪「開受控頁 → status/read → snapshot → 用 ref 操作 → 驗證值／頁面／伺服器計數」；輪流測 type、clear、select、toggle、click、scroll、wait。每輪帶唯一 run ID，保留初次失敗、恢復方式、重試次數；不能只計算最後重試成功。
- B03：先使用至少一個真實日常 MCP 客戶端，再使用獨立 stdio 測試客戶端驗證；有另一個可用的 AI 客戶端就再完成至少 10 輪。只有自製 runner 時必須明說，不能宣稱已驗證所有 AI。
- B04：未知工具、缺必要參數、錯誤型別、多餘參數、未知 JSON-RPC 方法、通知，檢查錯誤格式／isError，以及不合法參數沒有到 HTTP／頁面執行。
- B05：MCP 一筆 10 秒 wait 尚未完成時，同一 stdio 發 ping、status、diagnostics、takeover，確認 control 能回應。另排入測試頁 click 並在它開始前送 notifications/cancelled：不得送到 HTTP／增加 fixture 計數；已開始操作的取消只驗證抑制遲到結果，不宣稱回滾。
- B06：用有限數量的並發受控 wait 使 HTTP／MCP page queue 滿載，確認有限 BUSY／503、無無限堆積；control 仍可用，工作結束後能恢復。stop／stdin EOF 不拖著長佇列無限等待；沒有重複寫入。將 mock queue 測試與實機 queue 測試分開列。

#### C. 頁面／分頁／Port 一致性

- C01：操作使用實際 profile、activeTab、完整 url 作為 profile／tab／expectedUrl。故意送錯 tab 或 URL 必須 PAGE_CHANGED，頁面計數不可改變；前置條件不得自動幫你切分頁。
- C02：取得 snapshot 後換 snapshot、切頁、SPA pushState／hash 改 URL，再用舊 ref，必須明確 STALE_REF／PAGE_CHANGED；不能命中另一頁的同名控制項。
- C03：開總計 12 分頁、切 10 個測試容器，前後頁 100 次；每頁有不同 run ID，核對 status/read/snapshot/圖片所屬，無 URL、Session、表單結果串頁。記錄 LRU 重載及其合理限制。
- C04：wait／navigate／下載進行中，測試切容器、LRU 壓力、關 Tab／Popup 的允許／拒絕行為；被拒絕關閉的分頁 metadata 必須完整，允許關閉則只取消所屬工作，不影響別的 Session。
- C05：真實導覽、BFCache 返回、前後台和 Port 替換，核對 bridge 可恢復、舊回覆不進新 Port、沒有重送舊 click／submit。BFCache 未命中就列該子測試 BLOCKED；不偽造 pageshow.persisted。八次重連上限／observer 清理沒有裝置觀測能力時只列 JS mock 證據。

#### D. 文字與普通網頁操作

- D01：input／textarea／plain contenteditable 填入中文、英文、emoji、多行文字；clear 後讀值及事件／伺服器結果。普通表單由本人觸控也能操作；合成 keyboard 事件不能替代 Android IME 驗收。
- D02：readonly、disabled、aria-disabled、display:none、visibility:hidden、超過 20,000 字元及 file input 的 AI 填入，逐一核對拒絕或實際限制；不能假報寫入成功。遮擋、offscreen、複雜富文字編輯器另列 UX／相容性結果，不一概聲稱可互動。
- D03：scroll y=0 位置不變；上下／scrollTo／頁內搜尋／高亮／前後結果實際可見。
- D04：等待延遲元素能找到；不存在元素得 WAIT_TIMEOUT／isError。斷線或關頁後 observer／等待應收尾，恢復後新 wait 能正常完成。

#### E. 人工接管與程序重建

- E01：測試容器 manual=true 後，本人仍能返回、輸入／操作，AI 的寫入應拒絕，讀取／診斷仍可用；manual=false 後能接續同一個任務。
- E02：確認 takeoverStatePersisted:true；讓 Abwr 真正程序重建，再讀該容器 manualMode，必須仍為 true，AI 不得自行恢復寫入。重建後由本人明確交還。只關／開 Activity 不算程序重建證據。
- E03：在背景實驗模式已開啟、沒有前景 Activity 時，若能安全誘發系統回收／START_STICKY 恢復，驗證服務重建會安排 renderer。Android force-stop 是另外情境，不應期待它自行復活；無法重現系統恢復則列 BLOCKED，不用手動開 Activity 冒充。

#### F. 截圖、背景與鎖屏

- F01：前景 A／B 頁分別改 run ID／大字計數器，再要求 live JPEG；確認可解碼、尺寸正常、畫面內容符合當下頁面。不是只查 JPEG magic、檔案大小或不同 hash。
- F02：capture 途中切頁、關 Popup、Home／回前景：應得到所請求頁面的正確畫面或明確失敗，不能得到另一 Session／Surface 的舊圖；切頁後 last cache 必須失效或按契約明確標示舊圖。
- F03：背景實驗模式 OFF 和 ON 各做 Home、鎖屏兩種測試；每次從前景基線開始，在 1／10／30 分鐘 checkpoint 分別查 health/status/diagnostics/read/snapshot、受控輸入及 live screenshot，最後回前景重測。不把「mode OFF 無 live Surface」的明確拒絕當作假 live 的 PASS，也不把它誤報成具備背景操作能力。
- F04：每個 checkpoint 都記錄 mode、可見／鎖屏狀態、PID 或 uptime 是否變化、HTTP／錯誤代碼、耗時、backgroundRendererPhase、frame 數／age、capturedAtMs／ageMs、當前頁面標記／畫面內容。用 fixture 計數器的實際變更證實 fresh；時間戳／新 JPEG bytes 本身不夠。
- F05：有問題時依目前能力明確要求一次 background recover，核對 20 秒限速及結果；再測實際 fresh 圖。不無限重建、不將 retryScheduled 或 surface attached 當成功。回前景確認同容器登入與正常操作可恢復。
- F06：環境能持續執行時再做 8 小時長測，每 5 分鐘採樣健康與記憶體、每 30 分鐘做受控 DOM／畫面驗證，記錄起訖、睡眠／省電、失聯、恢復和耗電。先保留 30 分鐘階段報告及長測進度；長測未跑／中斷要標 NOT_RUN／BLOCKED，不能稱長期穩定，更不能外推成所有手機 24/7。

#### G. 登入、隔離與資料保持

- G01：fixture 登入測試：同容器多 Tab 共用登入；另一容器未登入。重開 App、程序重建、LRU 重載後同容器登入仍保持；驗證登入後實際受保護頁，而不是只看 Cookie 名稱。
- G02：本人以合法測試帳號完成 GitHub 及一般登入站登入；AI 接續已登入頁面的授權讀取／無破壞操作；切分頁／容器、回前景、重啟後再驗證。真實 Cookie／密碼不取出，報告只寫登入狀態及遮蔽後的路徑。
- G03：Google OAuth、Cloudflare／Turnstile 分開測正常流程、Popup URL/title/loading、Popup 返回／關閉、人工接管／交還、外部瀏覽器備援。外部備援不列 Abwr 自己、不產生循環；外部 Cookie 不假稱已匯入 Abwr。
- G04：身份驗證交給本人；成功要有登入後可用頁面，政策拒絕／挑戰頁要記原始現象及錯誤類型。網站拒絕本身不等於 Abwr bug，也不算已通過；區分站方限制、App 缺陷和需要協助，不嘗試繞過。
- G05：只刪本輪建立的空白測試容器，核對其他測試容器／原有容器的數量、書籤及登入狀態未變；正在操作或人工接管時刪除應拒絕。退休 context ID 不可立即重用；storageClearRequested 不等於已收到清除完成確認。
- G06：隱私設定描述與實際一致：contextId 隔離 Cookie／Storage；抗指紋目前為全 Runtime，共享設定。每容器獨立設備指紋若未實作要列功能缺口，不假稱測到。

#### H. 下載、上傳、Prompt 與日常 UI

- H01：一般及登入 fixture 附件，經本人確認下載→進度→完成；用測試原檔 digest 核對內容／UTF-8 檔名。只用 Gecko 原回應，不能靠匯出 Cookie 或另一個 HTTP client 重抓冒充。
- H02：慢速大檔取消、fixture 截斷、程序中斷、背景下載、切容器；完成／失敗／取消與 bytes 一致，未完成檔不能顯示完成，不能誤稱自動續傳。低空間不要填滿本人手機；無隔離測試環境就 BLOCKED。
- H03：本人操作下載的開啟／分享／另存，驗證檔案和 URI 授權範圍；分享只檢查 chooser／選取 URI，不對外發送。另存失敗時原檔仍保留。browser_downloads／HTTP 僅回正確容器 metadata。
- H04：以無私人資料的 image／PDF 等測試檔上傳、多檔選擇、取消、原頁跳轉／關閉後的舊 picker 回來，核對只有有效來源收到一次結果。沒有任意 upload API 就測 owner UI，不把它寫成 AI 自動上傳。
- H05：HTTP Basic Auth、alert／confirm／prompt、網站相機／麥克風／位置權限：本人允許／拒絕／取消、跳頁／關頁、切其他容器；不能把舊頁 Prompt 送到新頁，也不擅自自動批准權限。錄音／定位只用授權 fixture，不保存私人資料。
- H06：網址列／搜尋引擎、前進／後退／重載、Tab／Popup 返回鍵、首頁、書籤／歷史搜尋、頁內搜尋、桌面版、複製／分享／外部連結、淺色／深色／跟隨系統逐項實際操作。正常主畫面優先顯示網站內容；工具／Token 資訊不佔用日常瀏覽空間。
- H07：長按連結、網站權限中心、favicon、書籤管理、插件／使用者腳本及除錯功能盤點：有實作就操作驗證，沒有就列功能缺口與優先級；空按鈕不算功能，DOM 診斷不算完整 CDP。

#### I. 更新、簽章、記憶體與錯誤診斷

- I01：舊／新 APK 同簽章時用保留資料的覆蓋更新，前後核對既有容器、分頁、書籤／歷史、合法登入狀態；無可相容的舊 APK 就 BLOCKED，不能以乾淨安裝代替升級驗收。
- I02：查正式發行是否有持久 release signing key 與可重現的簽章流程；只記公開憑證與配置證據，不讀取／複製／上傳私鑰。僅 CI debug key cache 不符合正式 V1 條件，即使這兩次 debug 更新成功也不能將此項 PASS。
- I03：壓力／長測前中後採樣 Java heap、可取得的 Abwr／Gecko PSS、resident Sessions、CPU、電量、熱狀態；區分 Java heap 與 native／整機 RAM。只具備部分觀測能力就明說，記錄是否持續上升、崩潰／ANR／回收及恢復耗時，不杜撰省電或效能數字。
- I04：僅擷取相關 Abwr／Gecko crash／ANR 日誌並先遮蔽敏感資料；HTTP、MCP、DOM 的錯誤是否讓 AI 知道是 WAIT_TIMEOUT、PAGE_CHANGED、STALE_REF、BUSY、認證失敗或斷線。舊 native manual guard 如果只有 BAD_REQUEST，記為錯誤碼一致性缺口，不因它成功拒絕寫入而假稱完全一致。

### 6. 結果判定

依正式 Release Gate 填完下表；每欄要附實機結果、證據路徑與未完成原因：

| V1 P0 | 驗收 case |
|---|---|
| API 存活及認證 | A02–A04、F03–F05 |
| 分頁與容器 | C01–C04、G01 |
| Agent 50 輪工作流 | B01–B06、D01–D04 |
| 進行中操作保護 | C04–C05 |
| 登入狀態保持 | G01–G04 |
| 不誤刪資料 | G05、I01 |
| 截圖一致性 | F01–F02 |
| Background | E03、F03–F05 |
| OAuth 與人類接管 | E01–E02、G03–G04 |
| 安裝更新與簽章 | I01–I02 |
| 啟動／崩潰 | A01、I03–I04 |

- 每個 case／子測試標 `PASS / FAIL / BLOCKED / NOT_RUN`，另標證據類別 `SOURCE / CI / LOCAL_TEST / DEVICE / EMULATOR`。文件／mock／CI 不能補成 DEVICE PASS。
- PASS 要有實際結果和可追溯證據；FAIL 要有重現。裝置／權限／帳號／外部政策受阻用 BLOCKED；根本沒做用 NOT_RUN。case 部分通過時逐子測試列，不能只給父項綠燈。
- 50 輪工作流列 completed/attempted、初次成功率、恢復後成功率、重試及重複副作用數；故意送錯參數／過期 ref 的拒絕測試另算，不稀釋正常工作流。
- 發現跨容器／分頁資料混用、洩密、既有資料受損、重複提交或錯頁操作：停止該破壞性測試，保留現場並列 P0；仍繼續安全且獨立的驗收。
- 將 `V1_RELEASE_GATE.md` 每一個 P0 映射到 case／證據。P0 FAIL、BLOCKED 或 NOT_RUN 都表示尚不能宣告正式 V1；已知背景模式限制、release key 缺失不能藏在「全部通過」。
- Google／Cloudflare 的站方拒絕與 App 可診斷／可接管行為分開判定，不承諾一定放行。插件／腳本／指紋等產品缺口另列表，說明屬使用者需求、P1 或需要產品決策，不自行改寫 P0 門檻。
- 8 小時長測、更多 OEM／Android 版本是補充穩定性證據；未完成時說明範圍，不能用一台裝置的 30 分鐘結果宣稱普遍長期穩定。

### 7. 交付文件與給開發 AI 的建議

在獨立目錄 `abwr-acceptance-YYYYMMDD/` 保存以下檔案，完成後給我實際路徑；不用將報告推送 GitHub：

1. `acceptance_report.md`：版本／環境、執行起訖與時區、SOURCE/CI/DEVICE 分表、全部 case 結果、background mode／鎖屏 checkpoint 表、工作流成功率及效能、原有資料保留、V1 P0 對照、整體判定。
2. `results.json`：機器可讀 metadata 及 cases；每筆至少有 id、priority、outcome、evidenceType、expected、observed、attempts、durationMs、errorCode、evidencePaths。未量到的數值寫 null，不寫 0 冒充。
3. `issues.md`：每個問題列 P0/P1/P2、標題、裝置／版本、最小重現步驟、預期／實際、出現次數／試驗次數、證據、影響範圍、恢復方法；附可能相關的 source 路徑、修法與回歸測試建議。明確區分已觀察事實與根因推測，不能以沒證據的猜測定論。
4. `summary_for_codex.md`：可直接貼回開發 AI 的精簡交接，格式如下：

```text
被測版本／source／APK digest／簽章／CI：
真機／Android／AI 客戶端／MCP 協定：
總結果：PASS __ / FAIL __ / BLOCKED __ / NOT_RUN __
50 輪正常工作流：初次 __/50，恢復後 __/50，重複副作用 __
背景 OFF／ON；Home／鎖屏 1/10/30 分；8 小時長測：
登入／容器隔離／資料保留／覆蓋更新：
V1 判定：可進候選驗收 / 先修 0.9.x / 缺必要驗收條件
P0 阻塞與最小重現：
最優先修正的 3～5 項（檔案位置、理由、回歸驗證）：
功能缺口／UX 建議與 P1/P2 排序：
需本人協助的最小步驟：
報告與證據實際路徑：
```

5. `evidence/`：遮蔽後的結構化請求／回覆摘要、計時、測試日誌、受控 fixture／runner 原始碼及啟動方式。截圖只保存受控 fixture；真實登入／私人頁面只做本機檢查，輸出文字摘要，不把私人畫面打包回報。掃描輸出確保沒有憑證或完整 OAuth query。

先執行可自動完成的部分；遇到必要的人工作業就列具體步驟，繼續其餘項目。完成第一階段就給真實報告，長測如仍在進行給進度與完成條件；結束後更新同一份報告。最終結論要明確回答：**這一版能否日常使用、能否被 AI 穩定使用、什麼阻止 V1、下一輪該先修哪些問題，以及修好後怎麼重驗。**

ADB forward／reverse 與裝置選擇命令參考：[AOSP 官方 adb 手冊](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md)。
