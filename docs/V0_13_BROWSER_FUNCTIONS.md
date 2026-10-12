# Abwr v0.13 瀏覽器功能與驗收紀錄

版本：0.13.0 / code 22。2026-10-12。接續 v0.12 及尚未提交的 HTTP／Surface 修正，以使用者要求的日常瀏覽器完整度為主。

## 功能入口

| 類型 | 真實功能／入口 |
|---|---|
| 瀏覽 | 底部網址搜尋、前後頁、載入中停止／重新載入、首頁、桌面網站、外部 HTTP(S) 連結 |
| 分頁 | 真 GeckoSession 分頁、搜尋與真縮圖、多選、分頁群組、群組改名改色、新增及移動、關閉分頁復原 |
| 容器 | 獨立 Cookie／Storage、多帳號正常登入、建立切換刪除、同名重建使用新的內部 contextId |
| 無痕 | 選單 → 新增無痕瀏覽。使用 Gecko private mode；不寫入歷史、分頁、群組、接管狀態與磁碟縮圖。App 程序重啟不恢復無痕容器。主動下載／匯出的檔案保留 |
| 網頁操作 | 原生鍵盤、觸控、拖曳與滑動；DOM 表單、下拉選單、可見 label 勾選、shadow root、跨來源 iframe |
| 圖片連結 | 網頁長按 → 新分頁、無痕開啟、複製、分享與收藏；圖片／媒體可另開分頁或另存（快速另存上限 2 MB，網站附件下載走下載管理器） |
| 閱讀 | 原生頁面尋找、閱讀檢視、可選取文字、50–200% 字體比例、允許手指縮放 |
| 網站 | 選單 → 網站資訊與權限；真實 TLS 資訊、按容器查詢與調整網站權限、定位／通知／跨站儲存、攝影機／麥克風來源選擇 |
| 媒體 | 網頁 HTML 全螢幕事件隱藏瀏覽器列，Android 返回先退出全螢幕；由 Gecko 處理媒體解碼 |
| 書籤歷史 | 搜尋、逐筆開啟複製分享刪除；清除目前容器歷史；Firefox／Chrome Netscape HTML 書籤匯入匯出（250 筆上限，合併去重，不執行匯入 HTML） |
| 檔案 | 網站單／多檔案上傳由 Android 選擇器完成；下載進度／取消／開啟／分享／另存／移除；選單 → 另存網頁為 PDF、目前畫面 JPEG 或文字 TXT |
| 分享整合 | 系統分享／接收分享、Android 預設瀏覽器角色、其他瀏覽器接手、主畫面網站捷徑、本人點選的 mailto／tel 等外部 App 連結 |
| 隱私 | Gecko ETP、Cookie 分區、GPC、全域原生指紋防護；不能稱為每容器獨立指紋 |
| 擴充 | Mozilla Android 擴充商店、Gecko 簽章與權限確認、啟停更新移除、popup 與 options；可安裝廣告攔截／完整腳本管理器 |
| 使用者腳本 | .user.js 匯入編輯啟停、@match／@exclude-match／@run-at；Firefox userScripts sandbox；有限 GM API，完整 GM 功能交由完整管理器 |
| DevTools | 原生 Console／Network／DOM／Storage；opt-in 目前文件錄製、有限且遮蔽敏感欄位的請求／回應本文、DOM 屬性／CSS、HTTP 請求工具 |
| HTTP 與 AI | 同來源 page Fetch 使用目前容器 Cookie；其他來源 native 不繼承 Cookie；POST 等方法、base64 二進位、真正增量串流／poll／SSE／取消；本機 Bearer API、MCP、人工接管 |
| 背景 | service-owned GeckoDisplay、有限 Surface 重建、工作 lease、非 UI 容器截圖；每次核對文件與 Surface，不能將快取當即時截圖 |

## 本輪修正

- POST 路由統一使用 X-Browser-Profile；header／body 衝突明確拒絕，資源回應包含 profile。
- 新容器使用獨立內部 contextId；刪除後可重建同名容器，保留舊有容器 contextId。
- document_start bridge 連線、就緒後第一個載入、delegate 重試與完整文件 load 核對。
- 切換後重新綁定全新的 GeckoView Surface，遮蓋到新文件 paint；首次 JPEG 的顏色必須正確。
- 驗收腳本版號與 MCP 工具數核對更新，不略過實際 workflow。
- 修正測試 fixture 被第二個 do_POST 覆蓋，HTTP Cookie/header/body 的驗收現在真正生效。

## 驗證狀態

SOURCE 已實作。Python 37、JS 六組、Kotlin JVM 49 與 Debug 編譯通過。最終整合／產品 APK／長背景正在驗收；SHARP 本版未測。各輪結果不混稱為同一 APK 的整輪 PASS。

已完成的兩輪 CI（38227585602、38229543098）均通過前 13 組核心操作，包含 HTTP／實際 MCP 各 50 輪、容器 header、同名重建 Cookie context、首次截圖像素與跨容器隔離。尾端工作台與全螢幕尚未通過，未當作整輪 PASS。

排查已修正三項：腳本編輯驗收在鍵盤遮擋時輸入到錯欄位；腳本 Native Messaging 通道名稱包含 Firefox 不接受的連字號；Android 13+ 返回與模擬器首次全螢幕提示未處理。真腳本啟用、Network 本文、簽章商店安裝及無痕結果由最新 CI 重驗。CI 38234382128 證明服務已連線，找到空 excludeMatches 陣列違反 Firefox schema；再修正驗收中的含 port @match 及 fullscreen 讀取方式。該輪首次開頁被啟動首頁覆蓋，造成前五項連帶 FAIL；已將第一次替換導航延後到 Gecko 初始 window 開始，不重送已發出的操作。這輪仍是 FAIL，後續結果待填。

擴充現在接收真正的 active tab 切換；原生閱讀／TXT 可明確請求最多 100000 字元，AI 預設仍為 9000。Firefox 腳本 @match 不包含連接埠，如 http://localhost/* 會匹配該 host 的所有 port。

CI 38252479833（PR head `f7ee09fceb30c72296fbf5ff5e5038e634b9e5b0`；實際 Debug 內嵌 sourceRevision `f2bee02b4b81bef45eb899f85c651b57dec920e8`）已實際證明腳本服務連線、註冊並在網頁執行；無痕 Cookie／不寫磁碟、關閉分頁復原、原生拖曳與全螢幕 PASS；含容器 Cookie 的 5 MiB 下載經 SHA-256 核對，PDF 與 HTML 書籤經 Android 選擇器存出。附件下載後不再把原文件 bridge 當成過期文件。

該輪核心 12 組 PASS，另一組在關閉分頁時遇到自動縮圖仍執行，回傳舊式 BAD_REQUEST，並非分頁已關閉；已改為可重試的 BUSY／503。CI 38256433027 將腳本、DevTools 與 Mozilla 商店拆成獨立驗收，保存每次實際 inspector 回應；原生「停止載入」也已證明可恢復原文件身分。該輪 Debug 編譯、Python 37、JS 六組、JVM 49 與核心 13 組全部 PASS；腳本、無痕／拖曳／全螢幕、檔案另存三組 PASS，另外兩組 FAIL，仍不是整輪通過。

該輪 DevTools 的實際回應指出 Console 轉送觸發「Permission denied to access property length」，讓網頁接下來的 Fetch 根本沒有送出；已改用 content realm 的 Reflect.apply，並測試轉送原參數、不讀取物件 getter、不截短網站真正的回應、本文遮蔽及停止記錄後不再保存本文。Mozilla 商店搜尋、簽章下載、權限與 Tampermonkey 5.5.0 安裝已成功，但延後開啟的首次安裝歡迎頁蓋住「開啟」按鈕；驗收現在先關閉已觀察到的該擴充視窗，再驗證 popup／options 的真實內容。商店本地化欄位的 JSON null 也不再顯示為擴充名稱。下一輪重驗結果待填。

最終 main 驗收將直接安裝固定簽章、不可 debug 的產品 release APK；只在新的 Actions 模擬器透過 adb root 取得自己的測試資料，仍拒絕真機。核對實際版本、code、簽章、sourceRevision 後，才執行全部快速流程及 1／10／30 分鐘關螢幕新文件截圖。

CI 38258138715 的 Python／JS／JVM／Debug 編譯 PASS，但模擬器工具將多行安裝條件逐行執行，停在 shell syntax error，尚未安裝 App，沒有瀏覽器整合結果。已將安裝流程移到獨立 Bash 腳本並加入安裝前真機拒絕與產品測試的 Actions／root 檢查；這輪屬驗收管線 FAIL，不混稱核心功能退步或修正已驗證。

CI 38259078975 已通過真實 Console 與請求／回應本文錄製，保存 POST／200、nonce、遮蔽的密碼與回應本文；Network 串流沒有更改網頁實際回應。腳本、無痕／拖曳／全螢幕、檔案另存也 PASS。擴充安裝成功但開啟面板仍 FAIL；已修正 popup 回傳的 GeckoSession 必須先 open，關閉擴充視窗後恢復原 tab 的 active 狀態，及 install 的初始 metadata 不覆蓋已到達的 onReady 完整資訊。Browser action 與 page action 也分開保存，避免合併不同類型造成錯誤；API 現在保存有限的 action 狀態作為驗收診斷。

該輪核心 Stop 測試的 10 秒回應在 UI 等待 idle 時已完成，實際畫面已是新的「Slow page completed」，因此要求仍停在原頁是錯誤的測試前提。fixture 改為由測試端釋放回應，讓它在原生停止按鈕真正點下並核對文件之前不可能載完；不是把錯誤判定改成 PASS。後續 iframe 測試先開自己的 fixture，避免第一組失敗造成其他四組連帶失敗。這輪整體仍 FAIL，下一輪重驗。

CI 38262024151 的瀏覽器五組全部 PASS：實際腳本、Console／Network 本文、Tampermonkey 簽章安裝／歡迎頁／popup／options、無痕／拖曳／全螢幕、5 MiB 下載／PDF／HTML。核心 Stop 與 HTTP 50 輪也 PASS，但 MCP 第 14 輪 navigate 時主程序 Gecko 發生 SIGSEGV／null pointer，堆疊為 libxul BuildId `c0f85f2760fb8cef9ab10bc3758a05d7e1f0f8fd`、首位移 `0x48c70e9`；程序自動重啟導致後續 UI 兩組連帶 FAIL。這是本版實際 P0，仍不能交付為完成版，也不能把之後單次 PASS 當作根因已修好。

一般 tab 啟用已恢復只在 Session 改變時通知 Gecko；擴充視窗關閉與按下 action 時才明確恢復 active tab，避免在一般操作重複送啟用事件。此為生命週期收斂，尚未證明是原生崩潰根因。加入 Mozilla 公開符號解析，只提交 lib 名稱／BuildId／指令位移，不提交 tombstone、頁面或憑證；保存原始崩潰與符號結果，繼續追具體函式。

CI 38264380897 的編譯、Python 37／JS 六組／JVM 49，以及核心 13／瀏覽器五組全部 PASS；但不以一次成功抹去 C11 原生崩潰。符號已成功匹配該 libxul build，定位至 JNI `ProxyNativeCall<EventDispatcher>` 的 `MOZ_CATCH_JNI_EXCEPTION`，並非已證明記憶體不足。Gecko 的 Port.disconnect 會排入 native event 並立即 shutdown named dispatcher，與該堆疊相符，這是依原始碼作出的生命週期風險判斷。

下一輪不再由 App 搶先呼叫原生 Port.disconnect；只退役本機記錄、立即失敗舊操作，由 content pagehide／Gecko 自己關閉。加入弱引用的連線狀態，退役或已關閉的 Port 不再接受晚到的 ready、不再傳送 ACK／取消／命令。載入尚未真正換文件時保留舊 Port 與 iframe，讓停止載入恢復原頁；新文件接受後才淘汰舊 frame。HTTP／MCP 各增加至 100 輪，同時補上 JNI／Java 錯誤收集並遮蔽本機 Token。這些改動須由新 CI 證明，不先宣稱原生根因已徹底修復。

本輪增加行為測試：書籤 HTML 交換與不可信網址排除、無痕資料不入分頁復原、原生拖曳邊界；Android 上驗證 HTTP/MCP 各 50 輪、首次截圖與容器隔離、原生 UI、真腳本執行／Network 本文／商店簽章安裝，及無痕 Cookie／不寫磁碟、分頁復原、HTML 全螢幕，及需要容器 Cookie 的 5 MiB 真下載、SHA-256、原生 PDF 和 HTML 書籤另存。

## 尚未支援或不能保證

全部桌面 Firefox／Chrome 功能並不等於 GeckoView 提供的嵌入式能力。本版不提供 Firefox Sync、Chrome 同步、瀏覽器自建密碼庫、完整 CDP、完整 GM API、任意 evaluate API、全頁長截圖、每容器代理或獨立設備指紋；也不包含網站驗證求解器。

Google／Microsoft OAuth、Passkey、DRM、OEM 省電與 8 小時背景仍要在 SHARP 真機測試。站方可能要求本人 2FA 或確認。安裝及編譯成功不能替代這些結果，未滿真機 V1 門檻不改稱 1.0。

## 更新與開啟

下載本輪 `Abwr-signed-apk` 的 ZIP，解壓 `app-release.apk`，在 Android 點 APK 覆蓋安裝；不要卸載或清資料。`Abwr-dev-apk` 是獨立 .dev 測試 App，不是產品更新檔。

開啟 Abwr 後原本登入容器應仍在。一般功能從右下選單進入；擴充商店、腳本與 DevTools 有獨立原生頁面。SHARP 現場流程見 [驗收 Prompt](V0_13_ACCEPTANCE_PROMPT.md)。

AI 連線資訊放在「設定 → AI 連線」，本機 Token 不提交到對話或 Git。
