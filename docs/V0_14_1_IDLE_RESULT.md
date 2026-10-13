# v0.14.1：產品更新與完整閒置驗收

更新：2026-10-14，Asia/Taipei。下一個 AI 先讀本檔，再讀 [完整工作交接](V0_14_1_COMPLETION.md) 與 [手機單機驗收](V0_14_1_TERMUX_EVIDENCE.md)。

## 本輪修正

CI 與 Termux 原本累計到第 1、10、30 分鐘，在每個檢查點之間會讀 API、導覽及截圖；因此連續閒置約為 1、9、20 分鐘。現在每輪在前一輪操作完成後，重新連續閒置 60、600、1800 秒。報告分別保存要求秒數、實際閒置秒數與累計時間，閒置本身合計 41 分鐘；截圖前後核對 PID 與 processInstanceId，發布門檻要求三輪皆滿足實際秒數。

出口驗收改用本次 SHA 核對通過的回應所解析出的 IP。`/slots` 的歷史 IP 映射保留供診斷，本次沒有讀到 IP 時會拒絕將舊值算成已驗證出口。新增的行為測試涵蓋截圖耗時、最後一次截圖期間程序重啟與舊出口映射。

## PR #7 合併後的固定簽章產品驗收

使用者回覆「都可」批准 PR #7；已於 2026-10-14 01:50:57（Asia/Taipei）合併 main。**固定簽章產品 [CI 38441974654](https://github.com/Tsenjii/Abwr-/actions/runs/38441974654) SUCCESS**，實際安裝的 APK sourceRevision 是 `519d82f6db24f6b20d5fb7467b16201c20915be6`；App／API／content bridge／MCP／SDK 0.14.1、code 24、schema 4、82 tools。

Python 71、JS 六組、JVM 63（零失敗／錯誤／略過）、Debug／Release PASS。以下結果直接來自不可 debug 的 `dev.local.aibrowser`，原憑證與下載 APK 雜湊均已核對。

| Android | 全套結果 | 每輪要求／實際連續閒置秒數 | 三輪結束累計秒數 | 背景 PID | 升級與像素 |
|---|---|---|---|---|---|
| 13 / API33 | 22 PASS、0 FAIL、0 NOT_RUN | 60/60、600/600、1800/1800 | 2461.7 | 10680 | 保留資料升級 PASS；藍、綠、藍 JPEG 像素 PASS |
| 15 / API35 | 22 PASS、0 FAIL、0 NOT_RUN | 60/60、600/600、1800/1800 | 2461.6 | 7876 | 保留資料升級 PASS；藍、綠、藍 JPEG 像素 PASS |

每個系統三輪均使用相同 PID／processInstanceId，螢幕不互動、背景 renderer active；各輪新 documentId、前進時間戳與不同 JPEG SHA。HTTP／MCP 各 100 輪；15 fixture profiles 循序隔離、一／二路唯讀各十輪、私密代理隔離與已測故障後不直連 PASS。最大已證明讀取並行為 2，寫入並行未量測；兩個 native crash buffer 均為空，既有崩潰根因仍保留追蹤。

原固定簽章 `adb install -r`：0.14.0/code 23 → 本次 0.14.1/code 24；舊版先等待 20 秒並重啟讀回 storage，再覆蓋更新。兩個 Android 均保留憑證、Token、context IDs、Cookie 隔離、localStorage、分頁、書籤及接管狀態；未清資料、更新後未重植 storage。

- [本次固定簽章 APK artifact](https://github.com/Tsenjii/Abwr-/actions/runs/38441974654/artifacts/11837510118)：APK SHA-256 `019edbf0b586379b923f77e83f29c1ede35090108c417b7c03fe6191145a0e23`，246,173,751 bytes；ZIP SHA-256 `a66dc1db499b1962d6cfcc035215332bd849578362931644da4dfd69326666ac`。
- 原憑證 SHA-256 `c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`。
- API33 artifact `11839773124`，ZIP SHA-256 `7f83e4874e4fad9a9ebba1aaf07088d7fdcfcd070b1c506d8a1847513e6d9ec4`。
- API35 artifact `11840172364`，ZIP SHA-256 `9d775be7b5ca7754f7db7e60d286497140d00134c9199ab01fae6052dfb4331c`。
- JVM artifact `11837240174`，ZIP SHA-256 `a96793b9a3a792f75c8e2625355740532c456e159ef8286103da457555153b98`；下載 XML 核對 63／0／0／0。

APK、兩套 Android 與 JVM 的下載 ZIP 均與 GitHub digest 比對成功；另外用 JPEG 實際像素核對顏色。[完整 main CI 機器證據](evidence/V0_14_1_MAIN_IDLE_CI.json) 包含 release job 的原始驗收摘要、每項來源與本地核對結果。文件提交可以比已驗 APK 來源更新。

既有 [v0.14.1 prerelease](https://github.com/Tsenjii/Abwr-/releases/tag/v0.14.1) 的 source `e20ffc9`、APK 及六個資產保留，資產 ID／digest／大小已逐項確認未變更；它的舊累計檢查點不會改稱獨立閒置。新來源的 APK 與實際完整閒置以本次 CI／artifact／證據為準。SHARP 真機、真實 15 出口與第三方帳號場景維持 NOT_RUN，非正式 V1。

## 先前來源與驗證

| 範圍 | 來源與 CI | 已核實結果 |
|---|---|---|
| 固定簽章產品 APK | source `e20ffc97559a2857acb5e5bd64b0d8b1b0f3f153`；[38426201545](https://github.com/Tsenjii/Abwr-/actions/runs/38426201545) | Python 68、JS 六組、JVM 63、Debug／Release；Android 13／15 各 22 組 PASS |
| 原簽章覆蓋更新 | 同一產品 CI，`adb install -r`，0.14.0/code 23 → 0.14.1/code 24 | 兩個系統均保留憑證、Token、context ID、Cookie 隔離、localStorage、分頁、書籤與接管狀態 |
| 修正分支編譯 | [PR #7](https://github.com/Tsenjii/Abwr-/pull/7)；head `6a8632cd729a7fa651f34755bb6801b59f44dd27`；[38431127089](https://github.com/Tsenjii/Abwr-/actions/runs/38431127089) | Python 71、JS 六組、JVM 63、Debug PASS；測試 APK sourceRevision 是 PR merge `d3a99cc02bd26c34c183786dfe92c2170a1751fc` |
| 新原語快速驗收 | 修正分支 Android 13／15 | 各四組 PASS；DOM 實際 `abc`、三個 trusted 按鍵、15 測試容器、兩個讀取 worker 各十輪、私密代理隔離 |
| 修正後完整閒置 | 修正分支 Android 13／15 | **PASS**：兩個系統各 22／22；各輪實際閒置 60／600／1800 秒，總測試時間 2461.5／2462.5 秒 |
| SHARP R8s 與真實出口 | 本環境沒有手機通道或已配置的實際出口 | NOT_RUN；仍是工程候選版，維持 0.x |

初次發布產品 CI 的 1／10／30 分鐘資料是累計檢查點，保留原始結果；完整連續閒置已由上述合併後固定簽章產品再次驗證。PR APK 的 applicationId 是 `dev.local.aibrowser.dev`，憑證是測試憑證；手機更新可使用上述本次 main 固定簽章 APK；下方初次發布產品保留其來源。

## PR 分支長測的實際值（合併前紀錄）

| Android | 每輪要求／實際連續閒置秒數 | 三輪結束累計秒數 | 背景 PID | 像素與文件 |
|---|---|---|---|---|
| 13 / API33 | 60/60、600/600、1800/1800 | 2461.5 | 9428 | 每輪新 documentId／前進時間戳／JPEG；藍、綠、藍像素 PASS |
| 15 / API35 | 60/60、600/600、1800/1800 | 2462.5 | 6673 | 每輪新 documentId／前進時間戳／JPEG；藍、綠、藍像素 PASS |

每個系統三輪使用同一 processInstanceId；螢幕皆不互動。兩個系統的 HTTP／MCP 各 100 輪，兩個讀取 worker 十輪與私密路由全部 PASS；native crash buffer 為空，原生崩潰歷史繼續保留追蹤。ZIP 與 GitHub digest 比對成功；另外用 JPEG 實際像素再次核對顏色。

- API33 artifact `11834860645`，ZIP SHA-256 `24513e8ec0f40a54b90b9ec02904f45942b3852f528fd684caa99c077f70d062`。
- API35 artifact `11834003917`，ZIP SHA-256 `005998b6399831af05fd6c6883f4e4a67c728a56b80d2839da9cc7abb9c3d69b`。
- JVM artifact `11830946669`，63 tests、0 failures/errors/skipped，ZIP digest 也已核對。
- 測試後整合 main `c825419` 的產品文件，保留其 APK、CI 與失敗紀錄；與已驗來源相比，App、bridge、scripts、tests、workflow 沒有額外變動。新增交接文件可比 APK sourceRevision 新。

## 初次發布產品下載與資料保留

[v0.14.1 APK 與完整產品證據](https://github.com/Tsenjii/Abwr-/releases/tag/v0.14.1) 已發布為 prerelease。App／API／content bridge／MCP／SDK 0.14.1、code 24、schema 4、82 tools。

- APK SHA-256：`e419b4ed3f64bfd35c5682c9680bb44b1fd01267b5f1064766b59afec6cf39c6`。
- APK 大小：246,173,839 bytes。
- 原固定憑證 SHA-256：`c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`。
- 原版先經 20 秒 event-pump 等待，重啟後實際讀取既有 localStorage，再安裝新版本並再次讀取；兩個系統都成功保留。先前資料消失的機制仍未證實，異步落盤是假說。
- 保持原簽章覆蓋安裝；既有容器資料保留，不生成新 key。更新手機 APK 後同步 Python bridge／SDK，再重啟 MCP client。

## 下一個 AI 的接續工作

1. 讀 [機器可讀證據](evidence/V0_14_1_IDLE_CORRECTION.json)，核對每項的來源、APK 憑證及實際秒數。文件提交可比測試來源更新，不能因此把新文件 SHA 當已測 APK sourceRevision。
2. PR #7 已經使用者批准並合併，main 固定簽章產品 CI 38441974654 SUCCESS；批准與先前拒絕的歷史保留在機器證據。無須再為此合併詢問批准。
3. 合併後產品驗收已完成；依上方 artifact 取得新來源 APK，核對原憑證與 sourceRevision，再同步 repo/bridge。既有 v0.14.1 release 的來源與 APK 保留，不把它換寫成此次來源。
4. 有 SHARP 控制通道後，再按手機驗收指南測 OEM 前後台／鎖屏、正常帳號登入與實際出口；保存原生崩潰及程序退出紀錄。真機證據符合 V1 gate 才升 1.0。

一般瀏覽器操作由 AI／UI 共用權限；顯式接管才暫停 AI 寫入。維持 loopback、Bearer、原容器 context ID 與既有登入資料，未知結果的寫入先觀察、不重送。
