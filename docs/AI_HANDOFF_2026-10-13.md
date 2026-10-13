> **2026-10-14 長測更正：** 舊 CI／Termux 使用累計 1／10／30 分鐘檢查，期間有 API 操作；不能視為各輪連續閒置滿 10／30 分鐘。已修正獨立 60／600／1800 秒計時與發布門檻，新完整產品驗收待結果。進度見 [v0.14.1 完成紀錄](V0_14_1_COMPLETION.md)。

> **目前接手入口：[v0.14.1 完成工作與驗收進度](V0_14_1_COMPLETION.md)。** code 24／schema 4／82 tools；私密 profile 代理隔離、真實讀取併發量測、Android 13/API 33 與固定簽章保留資料升級已補實作，最新 CI 尚待結果。下方 v0.14.0 的 22 PASS 是先前產品證據，不混稱新版已過。AI/UI 共用一般權限；SHARP 真機與實際出口仍需各自證據。

# ABWR 目標與進度：下一個 AI 先讀

更新：2026-10-13，Asia/Taipei。這份文件與 [v0.14 契約](V0_14_AUTOMATION.md) 優先於 repo 內歷史 v0.11／v0.13 敘述。

## 目前結果

**v0.14.0 的程式、文件、Debug／固定簽章 Release、整合與背景長測已完成並合併 main。SOURCE／CI PASS；SHARP R8s DEVICE 未測，非 V1。** 不要重做已通過的 G1–G20；接續真機與未驗證的平台邊界。

| 項目 | 最新已核實值 |
|---|---|
| Repo | https://github.com/Tsenjii/Abwr- |
| 功能／焦點修正 PR | https://github.com/Tsenjii/Abwr-/pull/4 ＋ https://github.com/Tsenjii/Abwr-/pull/5，均已合併 |
| 產品程式 sourceRevision | 8b86c094775f7911a4e707cb1a10abf90c358eae |
| 版本／code／schema／MCP tools | 0.14.0／23／4／82 |
| Main CI／產品證據 | https://github.com/Tsenjii/Abwr-/actions/runs/38386576654，22 PASS／0 FAIL／0 NOT_RUN |
| APK／憑證／長測詳細值 | [V0_14_BUILD_RESULT.md](V0_14_BUILD_RESULT.md) |
| 功能 PR head／CI | 79cea53c4003e0835a86da9c45264f7c8a0d84e7／38376084274，全四個 jobs PASS |
| 焦點修正 PR head／CI | 9a1481e26642ee13a7f621f788cd4deba755ecef／38382022918，all 整合快測＋automation PASS；第一次 all 因 AMO DNS 無地址 FAIL，保留 artifact 11804687348／原日誌，成功結果以重跑 attempt 為準 |
| 三語與編譯 | Python 61／JS 六組／JVM 63；Debug／Release／固定憑證 PASS |
| 真機接續指令 | [V0_14_DEVICE_ACCEPTANCE.md](V0_14_DEVICE_ACCEPTANCE.md) |

產品直接測試 dev.local.aibrowser；PR 的 dev.local.aibrowser.dev 與其臨時 debug 憑證不能代替產品更新。文件提交可以比產品 sourceRevision 新，仍以建置紀錄中的程式來源為準。

## 使用者目標與最後修正

使用者要求完成 ABWR（Android GeckoView＋127.0.0.1:8765 HTTP＋MCP stdio）的通用自動化差距 G1–G20、Termux 單機 Python 適配層、15 組測試設定檔、文件、三語 CI、編譯與 repo 交接。新需求與本檔優先於舊描述。使用者已授權 main 發布，繼續同範圍不再重問；使用原固定簽章，不產生替代 key、不卸載或清資料。

**使用者明確修正：AI 與人類共用一般瀏覽器操作權限。** 不因舊需求寫「只能人類」就把網站 prompt、權限、檔案與下載鎖成 UI 專用。API/UI 回答同一個 scoped broker 請求一次。manual takeover 是顯式暫停寫入，可由擁有者授權的 API 顯式交還；Android OS 授權必須真的成立，不偽造 grant。

全鏈路在同一支 Android：ABWR＋Termux Python＋本機閘道；abwr backend 不載入 Playwright 或讀 C:\...chrome.exe。stdlib SDK，requests 僅上層按需。網站是否登入／操作是否完成要讀可見結果；dispatched:true 不代表站點完成。

## 已完成的功能

- G1：pointer_path 原生 touch move/down/up＋atMs，2–256 步／10 秒內／平衡釋放；key_sequence 1–128 keys＋intervalMs，保留 bulk type。文件／Surface／接管改變會取消，不重送。DOWN 前 move 是 touch 定位，不宣稱 mouse hover。
- G2/G17：真正 Gecko response 事件，依 profile/tab，method/status／遮蔽 headers／時序／frame 與文件識別；16 tabs、每個 256 事件、afterSeq 消費、overflow 503 BUSY。新主文件沒有 DOM ID 時回 null，另保留 initiator/native ID，不挪用舊文件 ID。
- G3/G15：geometry／visibility／computed role／element center、DOM/find/links 分頁；allowlist readonly_probe 顯式 enabled:true 並 audit；frame＋expectedFrameUrl，open shadow 可讀，closed 不透明。
- G4/G18：布林 consented:true＋完整 profile/tab/documentId、可選 expectedUrl，未知欄位／型別／stale 先拒絕；same-origin storage debug export、預設遮蔽敏感值、metadata audit。沒有 raw Cookie／密碼／權杖匯出。
- G5/G16：document_changed／url_changed／load／frame_ready／network_idle／delay，Android Handler 事件泵；DOM 10 秒／原生 20 秒；WAIT_TIMEOUT 明示失敗。SPA hash 可以保留同一文件。
- G6：queue introspection，page 2/8、control 3/12，inFlightProfiles／pendingBridgeCommands，503 BUSY retryable。15 profiles 真實循序量測；同 profile tabs 共享登入，跨 profile 隔離，shared_gecko_runtime 不是獨立裝置指紋。
- G7：實際 Gecko tab cookieStoreId 的每普通 profile HTTP/HTTPS/SOCKS5 路由、分欄帳密、首導航前配置；實際 A/B／新 tab／TLS／SOCKS／失效不直連已過 CI；15 loopback slots＋控制面／冷卻落盤／本文＋事件 probe。
- G8/G14：同一次 live JPEG＋profile/tab/documentId/URL/尺寸/時間戳；MCP metadata＋image blocks；/last 明示 stale。新 JPEG >4 MiB 回 SCREENSHOT_TOO_LARGE，不拿舊 metadata 冒充新圖。
- G9/G11/G12：共享 alert/confirm/prompt/auth/popup/unload/repost/file／site permission/media／download broker，scopeToken、120 秒到期；takeover 持久化與 audit；file import、App readable content URI、非同步 staging（8 檔、32 MiB／檔、64 MiB／總計）與實際本文驗證；下載 metadata／cancel/remove／分塊 export。
- G10/G19/G20：錯誤契約、版本／sourceRevision／screen／wake／in-flight／lastScreenshotError，MCP 混裝標記與 SDK 拒絕；bounded audit；背景／LRU／會話保護與產品 1/10/30 分鐘鎖屏實證。force-stop 後執行不保證。
- Python abwr_shim、無桌機 backend 選擇、每 profile lock／最多兩個 page workers、未知寫入不重播、出口 JSONL 配額與最少 1.2 秒間隔；Node/Kotlin 範例、OpenAPI/MCP schema、Termux fixture 與 15 profiles 腳本。

API、邊界、HTTP/MCP 對照、錯誤碼與安裝見 [V0_14_AUTOMATION.md](V0_14_AUTOMATION.md)、[API.md](API.md)。

## 重要根因與已驗證修正

| 問題／歷史來源 | 修正與最新證據 |
|---|---|
| 5b0662／38285689441：MCP 截圖 mixed blocks 被當普通 JSON；初始導航身分競態 | MCP 按 content type 分派；主要 ready 立即發、tabIdentity 另發；最新 HTTP／MCP 各 100 PASS |
| 65df6bd／38292804657：FileProvider URI 接受後網站未讀到檔案 | Gecko resolveContentUri 只查 _data；App 非同步 copy 到自己可讀 file URI，API/UI 共用；實際 file.text＋檔名 PASS |
| ba9de1b／38322690384：pointer 未觸發 prompt | MotionEvent 保留 View raw/local 螢幕原點；CSS→JPEG x/y 同用 width 比率；截圖後重讀幾何並避開 IME；真實 prompt PASS |
| 345d8a3／38324988192：proxy 首導航 requests=[] | 實際 APK omni.ja 的 GeckoViewTab.userContextId 是 unsafeSessionContextId，ext-toolkit 再加 firefox-container-；首導航前 seed alias、tabs.get 查實際 store，不等錯誤頁 identity；已實證 A/B 路由 |
| b77ee1／38327189814：SDK HTTP probe 被 Unknown argument 拒收 | Native HTTP/MCP/OpenAPI 接受 tab/documentId/expectedUrl，保留原 scope guard；錯 scope 409 與 SDK 本文＋事件 PASS |
| 79cea53／38376084274：補齊嚴格同意與 P1 證據 | StorageExportSpec 拒絕字串 true、缺 scope、未知欄位；實際遮蔽 export／audit／opt-in probe／links 分頁 PASS |
| 9112240／38377804963：首份產品 all 在另存後鍵入仍空白，長測未跑 | 恢復 Android View/Gecko focus；系統視窗未交回則 NATIVE_INPUT_NOT_READY；Settings 往返保留同一文件、實際 abc 與 handled counters；PR 改 all＋automation，最新產品再驗，不交付失敗 APK |

## 下一個 AI 的工作

1. 先核對 main、實際安裝的 APK sourceRevision／固定憑證與版本；讀建置紀錄、真機提示詞、PROGRESS、API。不要把 debug artifact 當產品，也不要拿 v0.13 的 19 PASS 當 v0.14 證據。
2. 有 SHARP R8s 控制通道後，按真機提示詞驗覆蓋更新保留資料、前景／背景／鎖屏 1/10/30 分鐘、UI／OS 權限／file／media、低記憶體與無 Activity 恢復，保存真實 elapsed/PID／新文件／新像素／診斷／錯誤碼。
3. 真實代理槽位與目標域名、手機並發上限、逐站 OAuth／Passkey／DRM 分開量測。沒有真實出口就 NOT_RUN；配置數不能代表健康出口或不同 IP。
4. 只修可重現問題；維持 Cookie/context、登入資料、原簽章與 Loopback；不重送未知寫入、不自動換槽硬闖。改碼跑 Python/JS/JVM/Debug，再驗固定簽章產品；三語 schema 改動跑 scripts/freeze_automation_schema.py。
5. 把 source、CI、DEVICE 結果與可重現步驟更新回本檔／PROGRESS／建置紀錄。只有符合 V1_RELEASE_GATE 的真機證據才升 1.0。

## 尚未證明與固定邊界

SHARP 本版沒有前景／背景／鎖屏／覆蓋更新或 OAuth／Passkey／DRM 證據；當前環境沒有 adb／手機控制工具。CI 沒有 native crash 記錄不能證明所有 Gecko SIGSEGV 根因永久消失，真機要保存 native crash/exit-info。

普通 profile 路由已驗；private windows 的 cookieStoreId 共用，private scoped proxy 明確 NOT_IMPLEMENTED。Gecko 137+ 的 localhost/127.0.0.1/::1 目標維持本機直連，proxy server 本身可 loopback。沒有承諾 WebRTC/UDP 全出口隔離、full-page screenshot、任意 JS、獨立指紋或永久背景存活。

8765 固定只聽 127.0.0.1，除 health 外 Bearer；slots 7901–7915、control 9091、fixture 8877。產物在 Termux $HOME/.workbuddy-ai/{ledger,shots,state}，不寫 /sdcard。Token／原始 credentials 不進日誌、檔名或 Git；配額觸頂停止。測試帳號需自有或授權，遵守網站條款與速率限制；沒有驗證解算或身份偽造。
