# v0.10 — 根據完整 v0.9 手機回報修正

2026-10-11。SOURCE 已實作；Python 31 項、四組 JavaScript、24 項 Kotlin、Android Debug/Release 及八組模擬器驗收全部 CI PASS。HTTP 50 輪、實際 MCP 操作、背景 12 秒後新文件截圖已測；四個 UI 畫面已檢視。完整 [建置紀錄與下載](V0_10_BUILD_RESULT.md)。SHARP Android 14 真機重驗未完成，不能標示 V1。

## 本輪改動

| 回報 | 修正 | 驗證 |
|---|---|---|
| UI 難用、分頁數與目前頁混淆 | 底部網址列、繪製圖示、分頁/容器/選單卡片面板；明確標示使用中分頁；主分頁可關閉 | 四個 CI UI 畫面已檢視；網址正常、圖示置中、中文標題完整，真機觀感待本人驗收 |
| 狀態新 URL、內容舊文件、PAGE_CHANGED 誤報 | requested URL 存 pendingUrl；新的 top-level bridge 文件 UUID 才提交文件；同文件 hash/SPA 更新不冒充新載入 | PageIdentity JVM 測試；模擬器連續 50 輪 |
| 截圖只有頂端、背景 504 | 前景 GeckoView TextureView；背景直接使用 GeckoDisplay + ImageReader；核對完整 Surface 尺寸及文件/Surface 世代 | 模擬器使用紅色頁首、綠色/藍色頁底判定真實像素，不只驗證 JPEG 標頭 |
| 快照漏登入按鈕 | 可見表單/按鈕優先、associated label、open shadow root、分頁/篩選及 coverage；保留完整快照 metadata | JavaScript coverage 測試及真 Gecko 頁面 |
| iframe 不可操作 | all_frames bridge，每份子文件有獨立 frame ID、URL、快照回覆；可指定 frame 操作 | 同手機 loopback 不同埠的跨來源 iframe |
| 鍵盤/座標備援不足 | native key（含 Ctrl+A、Backspace、Enter）及 Gecko 原生 viewport touch；圖片尺寸與文件核對 | 模擬器檢查實際表單結果，dispatched 不當作成功證據 |
| disabled/hidden 成功假象、HTTP 忽略錯參數 | 原生共用參數驗證；CSS visibility/opacity、disabled/readonly；未知參數拒絕 BAD_ARGUMENTS | HTTP 實際負例與 JavaScript 測試 |
| baxia 類提示誤判不存在 | 多供應商可見訊號；沒有證據回 unknown/present:null，並不宣稱通過驗證 | coverage 測試；真站仍需本人驗證 |
| 記憶體只有 Java heap | 額外回報目前 App process PSS/private dirty/累積 CPU time | 不包含 Gecko 子程序、不是整部手機總量 |
| 本地驗收 server.py 失敗 | 新受控 fixture 使用 replace 插值，不以 format 處理 JS 大括號；實際 HTTP/POST/5 MiB attachment 測試 | Python fixture 行為 PASS；舊報告中的下載是否 attachment 不明 |
| 更新簽章衝突 | production Abwr 使用 v0.9 憑證固定 SHA-256，錯鍵直接阻止 release；開發 APK 改成獨立 Abwr Dev | 現有私鑰已回收及私密備份，release APK 憑證一致；持久 Actions Secret 遷移仍待完成 |

UI 參考 Mozilla 官方 [Android toolbar](https://acorn.firefox.com/latest/mobile/components/toolbar/android-rYWa0QpZ) 與 [Firefox Android 自訂工具列](https://support.mozilla.org/en-US/kb/customize-firefox-toolbar-android)：網址欄保留主要空間、常用控制容易觸及，進階工具移至面板。不是只更換文字或套色。

背景 renderer 本版預設開啟。`backgroundScreenshotGuaranteed:false` 與 `liveBackgroundScreenshotSupported:false` 在真機驗收完成前保持誠實；模擬器成功不代表 SHARP 鎖屏、OEM 省電或 8 小時工作都已驗證。背景只渲染 Abwr 網頁，沒有擷取手機桌面或其他 App。

## 簽章

可安裝產品是 `Abwr-signed-apk/app-release.apk`，package `dev.local.aibrowser`，版本 0.10.0/code 19。`Abwr-dev-apk` 是 package `dev.local.aibrowser.dev`，只供工程測試，不更新產品 App。

固定憑證 SHA-256：`c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`。與 v0.9 相同；更早版若使用不同且已遺失的私鑰，這份 key 無法代替。CI 不會新生成 distribution key，也不要求清資料或先刪 App。私鑰已回收及私密備份；[永久簽章設定](../signing/README.md) 尚待 Actions Secret，完成前不能宣稱簽章工作結案。

## 本地 AI 重驗 Prompt

```text
驗收 Abwr v0.10.0（code 19），git pull main，先讀 docs/V0_10_BUILD_RESULT.md、docs/V0_10_ACCEPTANCE_FIXES.md、docs/API.md、skills/abwr-browser/SKILL.md。
使用既有本機 Token；不要把 Token、Cookie 或私人頁面內容寫入回報。不要刪 App/資料。
覆蓋安裝前先記錄舊版 /v1/diagnostics installedApplication 的版本、package、certificateSha256，對照新 APK 憑證；若不符先回報，不能用卸載或清資料解決。
下載 CI 38145669297 的 Abwr-signed-apk，不要用 Abwr-dev-apk 更新產品；APK SHA-256 需等於 docs/V0_10_BUILD_RESULT.md。安裝後再核對版本、package 與憑證。
若可覆蓋安裝，核對原 Token/容器/書籤仍在。建立一個未使用的 ACCEPT10 測試容器，以下網頁、關分頁、下載只用該容器，不干擾既有資料。
手機上的 Termux 啟動 python3 scripts/acceptance_fixture.py（預設 8877、8878），App 載入 http://127.0.0.1:8877/。
使用 HTTP 和 MCP 分別核對快照 Name label、late 按鈕、shadow 按鈕、跨來源 child frame；點擊後讀結果。
從 status/快照取 pageDocumentId/documentId、tab、expectedUrl；HTTP 與實際 MCP 各連續導覽/快照/輸入/點擊/讀取 50 輪，分開記錄數量、錯誤、真正改變的頁面及耗時。
導覽後用舊 documentId 要 PAGE_CHANGED；重新取 snapshot 後舊 ref 要 STALE_REF。不要硬重送已發出的點擊或提交。
未知參數/numeric selector/missing target 必須 BAD_ARGUMENTS；disabled/visibility:hidden 必須拒絕；wait 缺元素必須 WAIT_TIMEOUT。
測 browser_press Ctrl+A、Backspace、Enter 與 browser_click_point：回傳 dispatched 後必須讀取實際結果。
前景 /color?c=green 拍 /v1/screenshot：檢查圖片完整尺寸、頂端紅色與底部綠色；保存非敏感測試圖。
按 Home，背景導覽 /color?c=blue，再拍 /v1/screenshot；必須有藍色底部且是新文件，不得使用 /last 冒充。
分別記錄背景 1/10/30 分鐘、鎖屏與省電情況，並附 diagnostics。失敗不要自動反覆提交點擊。
核對 UI 四畫面、tabCount 與列表、主分頁關閉、HTTP profile create/switch、人工接管及 5 MiB attachment 下載（本人點下載確認）。
記錄 process PSS/CPU/uptime（不包含 Gecko 子程序）；遇 App 重啟/ANR/連線中斷保留去敏感 logcat 與最小重現步驟，不能只重跑後標 PASS。
每項 PASS/FAIL/BLOCKED/NOT_RUN，附最小去敏感 request/response、測試圖/時間及原因。
普通登入流程由 AI 使用 DOM/frame/native input 自行完成可操作步驟；真正的身份確認/人機驗證交本人，不將未知挑戰狀態當作登入成功。
本輪檢查通過再提出 V1 建議；不能只依建置成功判定穩定。
```
