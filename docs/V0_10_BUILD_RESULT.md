# Abwr v0.10.0 建置與驗證結果

2026-10-11：正式/開發 APK、永久 Secret 簽章及八組受控模擬器驗收全部 PASS。這是交付真機重驗的 `0.x` 版本，SHARP Android 14、鎖屏、長時間工作與一般網站登入尚未重驗，非 V1。

## 可核對的版本

| 項目 | 結果 |
|---|---|
| Source commit | `968038fe857f413dc6472c98d22e0c0e07b9e4c1` |
| CI | [Actions 38149220497](https://github.com/Tsenjii/Abwr-/actions/runs/38149220497)，build/emulator 均 success |
| 產品下載 | [Abwr-signed-apk](https://github.com/Tsenjii/Abwr-/actions/runs/38149220497/artifacts/11706037181)，解壓後 `app-release.apk` |
| 版本 | `0.10.0` / code `19` |
| 產品 package | `dev.local.aibrowser` |
| 開發 package | `dev.local.aibrowser.dev`，不更新產品 |
| APK 大小 | 245,979,429 bytes |
| APK SHA-256 | `d6dafb59e82cd454e3560bbfc8aa52d0d115f0344546fe0e1d73ce9f12b6b377` |
| 憑證 SHA-256 | `c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`，與 v0.9 相同 |
| 實際模擬器證據 | [Abwr-emulator-evidence](https://github.com/Tsenjii/Abwr-/actions/runs/38149220497/artifacts/11706217600) |
| JVM XML | [Abwr-jvm-test-results](https://github.com/Tsenjii/Abwr-/actions/runs/38149220497/artifacts/11706695319) |

後續僅文件提交不改變上述已驗證程式碼或 APK。公開 artifact 依 GitHub 保存期限存在；下載後以 SHA-256 核對。

## 實際通過的檢查

Python 35 tests（新增四項簽章負例與私密檔案檢查）、四組 WebExtension JavaScript、Kotlin JVM 24 tests（failures/errors/skipped 都是 0），以及 Debug/Release Gradle 建置全部通過。Release 經 `apksigner verify` 並核對固定憑證；下載 APK 的 SHA-256 與 CI 輸出一致。

Android API 35 x86_64 Google APIs / Pixel 2 模擬器，CI 要求 RAM 4096M、heap 512M；實際 diagnostics 回報 App heap max 192 MiB，不能把要求值當作實測值。測試裝的是 Abwr Dev，以下八組均 PASS：

| 測試 | 真正確認的行為 |
|---|---|
| 文件與快照 | status/snapshot UUID 一致；舊 documentId 拒絕；可見表單 label、晚出現按鈕及 open shadow root 進入快照 |
| iframe | 不同 loopback port 的跨來源 child frame 可讀、可點；讀到點擊後文字 |
| HTTP 負例 | 缺 target、錯 selector 類型、未知欄位拒絕；disabled/hidden 不互動；缺元素 wait 失敗 |
| 原生輸入 | select 真正改變值；Ctrl+A/Backspace 清空；Enter 改變頁面；截圖座標 touch 後讀到結果 |
| HTTP 50 輪 | 導覽→快照→ref 輸入→點擊→讀結果，共 50 輪；每輪檢查相同主程序 PID |
| 實際 MCP | Python stdio 直接控制 Android HTTP/Gecko；協定 2025-03-26、54 tools；快照/ref、shadow/iframe、native key、JPEG/座標、isError 均有實際結果 |
| 分頁/UI/profile | tabCount 與列表一致、可關主頁、profile create/switch/delete 測試成功；保存瀏覽器/分頁/選單/容器四組 PNG/XML |
| 前景與背景截圖 | 前景綠色頁，Home 後等待 12 秒再導航藍色頁並新拍 `/v1/screenshot`；兩張皆 1080×1499，文件 UUID 不同，頁首紅色及頁底顏色正確 |

HTTP 50 輪不是 MCP 50 輪。MCP 是一組真實工具工作流；完整 MCP 50 輪仍列在真機 Prompt。

四個 PNG 已人工檢視（CI 38145669297，後續簽章提交未修改 UI 程式碼）：網址顯示 127.0.0.1、底部圖示置中、分頁使用中/數量清楚、中文標題完整、容器展示目前頁面。本輪同樣保存四組實際 PNG/XML；這不代替使用者對真機觀感與觸控的驗收。

本輪 logcat 與 `system-exit-info.txt` 沒有主程序死亡、Abwr ANR 或 fatal crash；HTTP 50 輪的主程序 PID 固定，背景 diagnostics uptime 約 136 秒，未重設。記錄中 Gecko 子程序是 `EXIT_SELF/status 0`。較早一輪有連線中斷與疑似主程序重啟，但當時晚取日誌遺失了原因；調整模擬器 RAM 並加入全程日誌/PID 檢查後通過，**不能據此斷言前次根因或長時間穩定性已解決**。

背景 capture 成功時 ImageReader swaps 仍是 0，因此不能再以沒有 swap callback 當作失敗。實際 `capturePixels()` 成功數、Surface 世代及像素才是證據。`/screenshot/meta` 的 `stale:true` 描述快取；本輪新 JPEG 來自 `/screenshot`，沒有使用 `/last` 冒充。

## 永久簽章已完成設定與建置驗證

v0.9 原私鑰已從既有 CI cache 回收，核對憑證後另存私密備份。Release 缺 key 或憑證不符會直接失敗；產品 package 維持不變，開發版另用 `.dev`。

永久 Actions Secret `ABWR_SIGNING_KEY_B64` 已設定。CI 38148623289 與本輪 38149220497 build log 都明確回報 `Signing key restored from repository secret`，release guard 與 APK 憑證核對通過。原 cache fallback、臨時 encrypted envelope 匯出及 recovery public certificate 已移除；Secret 缺失/無效不准回頭用 debug cache，release tasks 也要求明確 key path 及固定憑證。開發版由 Android 標準 debug signing 管理，與產品 package/key 分開。詳見 [signing/README.md](../signing/README.md)。

這完成工程端永久簽章與備份；真機 v0.9→v0.10 覆蓋安裝與資料保留仍須驗收。更早不同且已遺失的 APK 私鑰無法用 v0.9 key 代替；覆蓋安裝前須比較已安裝憑證。

## 下一輪真機驗收

請交本地 AI 執行 [v0.10 Prompt](V0_10_ACCEPTANCE_FIXES.md#本地-ai-重驗-prompt)。優先同簽章更新並保留資料、HTTP/MCP 各 50 輪、SHARP 背景/鎖屏 1/10/30 分鐘、日常 UI、人工接管、下載/上傳及普通登入流程。八小時常駐、12 分頁/10 容器、登入持久性等 V1 門檻仍未全部證明，見 [Release Gate](V1_RELEASE_GATE.md)。
