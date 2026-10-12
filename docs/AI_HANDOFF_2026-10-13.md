# ABWR 交接：目標、進度與下一步

更新時間：2026-10-13，Asia/Taipei。本檔供下一個 AI 直接接續；它與 [v0.14 契約](V0_14_AUTOMATION.md) 優先於下方各份文件中的歷史 v0.11／v0.13 描述。

## 最先要知道

使用者希望持續完成 ABWR，而不是只提出建議。新需求是 Android GeckoView 瀏覽器的通用自動化原語，以及手機上可直接運行的 Python 適配層。**使用者後來明確修正：AI 與人類共用一般瀏覽器操作權限。** 不要再因「只能人類」字樣，把網站 prompt、檔案、下載與網站權限硬鎖成 UI 專用。人工接管是使用者可選的暫停機制；本人授權的 API 也能顯式交還。

原需求包括 G1–G20、15 組測試設定檔、Loopback 埠配置、HTTP/MCP 對照、SDK、稽核、版本一致性與 CI／真機雙軌驗收。完整功能表、邊界、參數、錯誤碼及手機步驟見 [V0_14_AUTOMATION.md](V0_14_AUTOMATION.md)。

## 程式庫與版本

| 項目 | 位置／值 |
|---|---|
| Repo | https://github.com/Tsenjii/Abwr- |
| 開發分支 | codex/abwr-automation-parity |
| PR | https://github.com/Tsenjii/Abwr-/pull/4 （draft；尚未宣稱完成） |
| 起始 main | e484e180314b9b502bb45aebc8fc635b5778d56a |
| 第一輪候選 source | 5b066235efa4e960a32b1c07f6663823bd5438c1 |
| 第一輪 CI | https://github.com/Tsenjii/Abwr-/actions/runs/38285689441 |
| 第二輪候選 source | fc4884dda7b3538b05181eba4e0d900222b06810 |
| 第二輪 CI | https://github.com/Tsenjii/Abwr-/actions/runs/38291735994 （Android/JVM build PASS：58 項；後續追加修正須看最新 head，不能沿用核心／browser 結果） |
| 第三輪候選／CI | 65df6bd6930d32d90df5ecd7925109ac3fe38abb／https://github.com/Tsenjii/Abwr-/actions/runs/38292804657 |
| 第四輪候選／CI | 2ceb00820a42c0dda1e63ee52ad52ec92d479a9a／https://github.com/Tsenjii/Abwr-/actions/runs/38296665751 （FAIL，見下表） |
| 新候選版本 | App/API/content bridge/MCP/SDK 0.14.0，code 23，schema 4，MCP 82 tools |
| 已驗證產品基線 | main v0.13 固定簽章產品 APK；CI 38271166946，19 組及關螢幕 1/10/30 分鐘 PASS |

**新修正比第一輪候選 source 新。** 接手先看 PR head SHA／最新 Actions，再對照下方結果，不能用舊 CI 為新檔案背書。

第二輪之後另補了檔案原語整合驗收、API 回答後關閉仍在前景的系統選檔器、保留匯入檔案的原始顯示檔名，以及 proxy permission 所需的 strict_min_version。這些追加修正須以最新 PR head 的 CI 為準；第二輪 build PASS 不能為追加檔案背書。

## 已完成的程式實作

- `pointer_path`：原生 touch move/down/up＋atMs，整條路徑先驗證；2–256 步、10 秒內、最後必須釋放。換文件／Surface／接管會取消，不重送。DOWN 前的 move 只定位，不宣稱 Android touch 有桌面 mouse hover。
- `key_sequence`：逐鍵原生輸入與 intervalMs；完整 key 列表先驗證，保留 bulk type。回 dispatched＋siteCompleted:false＋文件／URL，後續仍須讀取結果。
- `geometry`／`readonly_probe`：bounding_box、center、visibility、computed role、viewport；allowlist probe 需顯式 enabled:true。DOM／find／links 加 offset/limit。
- 條件 wait：document_changed、url_changed、load、frame_ready、network_idle、delay；Android Handler 事件泵，宿主不 sleep 等網路；DOM wait 10 秒、原生條件 20 秒；失敗回 WAIT_TIMEOUT。
- 真正的 Gecko 網路事件觀察：依 profile/tab，status、遮蔽 response headers、時間、frame、文件識別與 redirect 事件；opt-in、16 分頁、每個 256 事件、afterSeq 消費確認、溢出明示 BUSY。
- 共享 prompt：alert／confirm／prompt／HTTP auth／popup／beforeunload／repost／file／網站權限／媒體來源。API/UI 回同一個請求一次，scopeToken＋session/document guards，120 秒過期；Android OS 授權仍由實際系統流程決定。
- 檔案 import：64 KiB bounded bytes 回可讀 content URI；file prompt 可以使用這個 URI 或 App 已有讀取權的 content URI。下載待確認請求共用 prompt，另有 cancel/remove/分塊 export。
- storage 除錯匯出：consented:true＋profile/tab/documentId，仍遮蔽敏感值。不把一般除錯匯出變成 Cookie／密碼／權杖原值導出。
- 原子截圖證據：同一張 JPEG＋profile/tab/documentId/URL/尺寸/時間戳；MCP 同時回 metadata text 與 image block；binary HTTP 端點帶 metadata header；last 仍為 stale。
- `/v1/queues`、固定診斷欄位、256 筆 metadata audit；無痕不落盤。接管狀態原本已有跨程序持久化，新增進入／交還稽核。
- `bridge/abwr_shim.py`：stdlib、Loopback、不載入 Playwright；每 profile lock／最多兩個 page workers；版本不符停止；不重送寫入；出口配額 JSONL／至少 1.2 秒寫入间隔；預設共用 direct bucket，避免把 profile 當不同出口。
- `bridge/slot_gateway.py`：7901–7915 最多 15 出口，9091 為獨立 Bearer 控制面；HTTP/HTTPS/SOCKS5 upstream、分欄帳密、透明 CONNECT、bounded relay、失敗／冷卻落盤；probe 經真實槽位，HTTPS 驗憑證，預期本文 SHA-256 才判健康，沒有自動換槽。
- schema、OpenAPI、JS/Kotlin 範例與 Termux fixture/15 組循序測試腳本已加入。

## 驗證與失敗，不要混稱

| 項目 | 目前可確認結果 |
|---|---|
| 本機 Python | **56 項 PASS**，含 SDK、不重送、版本拒絕、配額持久化、截圖識別、閘道實際 HTTP hop／內容 probe／控制面認證／冷卻；受控代理認證／CONNECT relay |
| 本機 JS | **六組 PASS**，含新網路事件狀態码、遮蔽、消費與 overflow |
| 第一輪 Android/JVM build | **PASS**；是第一輪 source，不能證明後續所有新檔案都編過 |
| 第二輪 Android/JVM build | **58 項 PASS**；source fc4884d，包含適配層、閘道、shared prompt／permission 與第二輪修正；追加選檔器修正須重新編譯驗收 |
| 第一輪 browser suite | **五組 PASS**：脚本、DevTools、擴充、無痕／拖曳／全螢幕、檔案／閱讀 |
| 第一輪 core suite | **FAIL**：MCP mixed-content 截圖還被序列化成普通 JSON list；另有一次新容器导航 completed/current documentId 不同 |
| 本機修正 | mixed text/image content 已正確分派；核心 ready 改立即傳送，tab 身分查詢另發 tabIdentity，避免延遲主要握手；尚須最新 CI 證明導航競態改善 |
| 第三輪核心／browser | 舊核心 13 組、HTTP／MCP 各 100 輪、browser 五組 PASS，15 profile 循序 PASS；新原語在檔案回讀 FAIL，不能稱整輪全綠 |
| 最新追加修正 | `BrowserFiles.kt` 非同步 staging 解决 FileProvider 的 _data 不可解析；API／UI 共用，原檔名、32 MiB／檔、64 MiB／總計、8 檔、16 暫存上限。新增網站權限、HTTP auth 與實際雙代理 HTTP／TLS／新 tab／失敗不直連驗收；尚須最新 CI |
| 第四輪 Android／browser／核心 | build／Kotlin 58、browser 五組、舊核心 13、HTTP／MCP 各 100、15 profiles PASS；新原語 pointer 後未取得 prompt，代理首次導航 TIMEOUT。沒有整輪全綠 |
| 最新本機追加修正 | CSS／JPEG x/y 共用 width 比率，避免軟鍵盤縮高造成誤點；精確等待 prompt 類型、失敗清理；新容器 about:blank，首連線前設代理；實際 SOCKS、SDK 本文／Gecko 事件健康測試；TLS relay 大本文、全池等候逾時、冷卻原子計數。尚須新 CI |
| 本版固定簽章產品／長測 | 不得沿用 v0.13 的證據；完成 PR 後仍要驗本版產品 APK |
| SHARP 真機 | **本版未測**：前景、背景、鎖屏 1/10/30 分鐘、覆蓋更新保留資料、OAuth／Passkey／DRM 均沒有本版證據 |

舊 v0.11／v0.13 曾有 Gecko native SIGSEGV。v0.13 新 bridge 生命周期修正後壓測與 30 分鐘沒有再現；不能宣稱所有真機長期根因已證明消失。本版必須保存 crash buffer、exit-info、PID 與 sourceRevision。

## 代理是尚待實證的重點

Gecko 的 Cookie/context 隔離，**不代表 WebExtension 一定回不同 cookieStoreId**。目前候選使用 proxy.onRequest＋tabs.get 查實際分頁的 cookieStoreId，避免 proxy/webRequest 仍回桌面 default store 的混用；新容器 about:blank 先以獨立 opaque context 建政策，再由 tabs.get 查首請求的 cookieStoreId；只有可區分、非重複的識別才接受 per-profile proxy；缺失／重複會拒絕，不能把最後一個容器的路由套到全部容器。設定 ACK 也不是外網健康證據。

尚未完成本版的 Android 代理路由與 TLS 實證。下一位 AI 必須用受控本機代理／fixture，核對兩個容器實際走不同上游、同容器 tabs 同路由、新 tab／popup／程序重建行為、失敗不直連。若目前 Gecko API 的容器識別不足，保留明確 NOT_IMPLEMENTED，而不是假裝支援；查官方原始碼找可核實的替代映射。

檔案失敗依据：[官方 IntentUtils.java](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/main/java/org/mozilla/gecko/util/IntentUtils.java) 的 resolveContentUri 只查 _data；FileProvider 不提供。不能只把接受 URI 的 dispatched 當成站點收到檔案。

官方查核線索：[Mozilla Bug 1643740](https://bugzilla.mozilla.org/show_bug.cgi?id=1643740) 記錄 GeckoView 的 geckoViewSessionContextId 與 userContextId 差异；[proxy.RequestDetails](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/proxy/RequestDetails) 說明 cookieStoreId。這是平台風險的依據，不能代替本版 runtime 路由證據。

Native anonymous HTTP 沒有容器路由；配置代理的容器使用它會回 PROXY_SCOPE_UNSUPPORTED。同源 page transport 使用目前容器的正常 Cookie，不匯出 Cookie。WebRTC／UDP 全面出口隔離尚未承諾。

15 槽位 example 是設定格式，沒有提供 15 個真實代理或 15 個不同 IP；不能把 example／循序 15 profile PASS 描述成 15 路並行或 15 個健康出口。

## 接手檔案與操作順序

1. 讀本檔、AGENTS.md、docs/API.md、docs/V0_14_AUTOMATION.md，核對最新 PR head 與 main。
2. 跑 `python3 -m unittest discover -s tests -p 'test_*.py'`，依次跑 `tests/*_test.js`；schema 改動後跑 `python3 scripts/freeze_automation_schema.py`。
3. 核對最新 Android/JVM CI 與核心／browser evidence。首要修新原語、MCP mixed image blocks、導覽身分競態、15 profile 行為；不要只調整測試，把失敗改成 PASS。
4. 補 API/真實 fixture 的檔案、網站權限、媒體與代理測試；維持背景同一份 session、未知結果不重送、不清除已有登入資料。
5. 只有最新 head 的 required checks 真正通過，才整理可交付 APK。產品仍用原固定簽章，不能產生新 key 或要求先卸載。
6. 手機驗收用 Termux、同一支 Android、Loopback API。新增 `scripts/termux_acceptance.py --create-test-profiles --profiles 15`；只建立明確的 TEST_AUTO_* fixture 容器，cleanup 需顯式旗標，不刪既有帳號容器。
7. 真機與長測證據各自記入 docs/PROGRESS.md、V0_14_AUTOMATION.md 與本檔；無證據就寫未測。V1 要過 release gate，不能僅因編譯綠就改成 1.0。

## 固定約束

ABWR 主埠只聽 127.0.0.1:8765，除 health 外 Bearer。Fixture 8877；出口 7901–7915；控制面 9091，檢查衝突。Token 不進日志、檔名、Git；Python產物在 Termux 家目錄 .workbuddy-ai/{ledger,shots,state}，不寫 /sdcard。

同 profile 的 tabs 共享登入，跨 profile 不共享；指紋防護屬 shared_gecko_runtime。通用自動化不包含身份偽造、驗證求解、指紋輪替或速率限制繞過。配額触顶停止，不換槽硬闖。一般網站互動與 API/UI 權限共用，不代表網站或 Android 已經授權成功。
