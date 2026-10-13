# v0.14 真機接續驗收提示詞

這份提示詞供有 SHARP R8s／Termux／ABWR 存取能力的下一個 AI 接續。它不是 DEVICE PASS。先讀 `docs/AI_HANDOFF_2026-10-13.md` 與 `docs/V0_14_AUTOMATION.md`，以其中的產品 sourceRevision、CI、固定簽章與 APK 雜湊為準。

## 權限與測試範圍

一般瀏覽器操作由 AI／UI 共用同一個權限與 prompt broker。不要再以「只能人類」拒絕本機擁有者已授權的網站 prompt、權限、檔案或下載操作。顯式 manual takeover 仍暫停寫入，可由擁有者授權的 API 顯式交還。Android OS 的授權必須實際成立；沒有權限時記錄 SYSTEM_PERMISSION_REQUIRED，不能偽造已授權。

使用自己的 fixture 與自有／授權帳號。通用測試不包含驗證解算、身份偽造、指紋輪替或繞過配額。不要靜默送出購買、付款、刪除既有容器等操作。未知結果的寫入先觀察效果，不能自動重送。

## 執行順序

1. 在既有產品上核對固定簽章，再覆蓋安裝 v0.14／code 23；不先卸載、清資料或刪除既有帳號容器。記錄更新前後的容器、書籤、分頁與登入狀態，確認保留。
2. 在同一支手機更新 repo 及 `bridge/mcp_stdio.py`，重啟 MCP client。核對 App/API/contentBridge/MCP/SDK 均為 0.14.0、schema 4、82 tools。保存 diagnostics 的 sourceRevision 與 certificateSha256；混裝即停止。
3. ABWR 只聽 `127.0.0.1:8765`，除 `/health` 外需要 Bearer。Fixture 8877；槽位 7901–7915；控制面 9091。檢查埠衝突；Token 留在 Termux 環境，不放日誌、截圖檔名、Git 或驗收回報。
4. 跑 `python3 scripts/termux_acceptance.py --create-test-profiles --profiles 15`。它每次建立唯一 TEST_AUTO fixture 容器，循序量測 bridge Ready／resident sessions／截圖。需要清理時加 `--cleanup-created`，只清理本次建立的測試容器。此腳本不證明 15 路並行、15 個不同 IP 或登入成功。
5. 用 fixture 驗證 P0：系統選檔／另存畫面返回後 viewHasFocus/windowHasFocus 與逐鍵輸入的實際 DOM 值；系統仍持有視窗時 NATIVE_INPUT_NOT_READY；move/down/up 的實際可見結果；document_changed／URL／load／frame_ready／network_idle 的匹配與 WAIT_TIMEOUT；snapshot refs 過期；跨 frame 要帶 expectedFrameUrl。dispatched:true 不當作網站完成。
6. 用共享 prompt API/UI 驗證 prompt／HTTP auth／file／site permission 的 scopeToken、過期、只回答一次；檔案需讀回實際本文與檔名，下載需核對實際 bytes。確認 manual takeover 重建後仍暫停，顯式交還與回答皆有 metadata audit。
7. 驗證 opt-in 網路事件的真實 status、遮蔽 Set-Cookie／敏感 headers、tab/profile 範圍、afterSeq 消費與 bounded overflow BUSY。loadSucceeded 不能推論 HTTP 200。storage export 需精確布林 consented:true、profile/tab/documentId；字串 true、缺少 scope 或過期文件均拒絕；敏感值保持遮蔽且 audit 不含值。
8. 若手機有實際授權代理，先建空白測試容器再設 server/username/password，驗證首次導航、新 tab、HTTP 與 HTTPS／TLS、SOCKS、指定目標本文雜湊＋Gecko response event。停止該上游後確認不直連、不切槽。Gecko 的 localhost／127.0.0.1／::1 目標維持本機直連；代理 server 本身可用 loopback。私密容器的 scoped proxy 在本版明確 NOT_IMPLEMENTED。沒有真實出口就記 NOT_RUN，不憑設定 ACK 宣稱健康。
9. 將測試頁切到背景／鎖屏；從診斷確認 viewAttached:false、screenInteractive:false。分別在實際經過 1／10／30 分鐘後使用不同 fixture URL／document，核對新 documentId、capturedAtMs、實際綠／藍 JPEG 像素與 metadata 一致，保存 PID／screenInteractive／wake lease／lastScreenshotError。需要量測閒置時不要持續輪詢或常駐持有 wake lock；等待網路事件則用 ABWR wait 事件泵。
10. 回前景確認同一份會話與登入狀態。另驗無 Activity 恢復、系統 LRU／低記憶體與 Android 權限提示；force-stop 後不宣稱任務仍保證執行。Google／OAuth／Passkey／DRM 要分站記錄結果，不能由一般 fixture 推論通過。

## 保存證據與回報

所有檔案在 Termux `$HOME/.workbuddy-ai/{ledger,shots,state}`，不寫 `/sdcard`。截圖 filename 含 profile/tab/documentId/時間戳；URL 的 query/fragment、Cookie／密碼／權杖原值不能進回報。若背景 live 擷取失敗，保存可操作錯誤碼；不得把 /last 舊圖當即時證據。

回報包含：版本與 APK sourceRevision／SHA-256／憑證、裝置型號與 Android、每項 PASS／FAIL／NOT_RUN、前景／背景／鎖屏實際 elapsedSeconds、同一文件或新文件判定、錯誤碼、PID／native crash buffer／exit-info（能取得才附）、可重現步驟與已遮蔽證據。把結果更新進 repo 交接檔與 PROGRESS；沒有手機證據就維持 DEVICE 未測，不能升為正式 V1。
