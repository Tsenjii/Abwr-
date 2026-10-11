# Abwr v0.10.0 建置與驗證結果

2026-10-11：Android 建置與受控模擬器驗收全部 PASS。這是交付真機重驗的 `0.x` 版本，SHARP Android 14、鎖屏、長時間工作與一般網站登入尚未重驗，非 V1。

## 可核對的版本

| 項目 | 結果 |
|---|---|
| Source commit | `b07e601c21770a3f78a8d7d393036be28d2a8815` |
| CI | [Actions 38145669297](https://github.com/Tsenjii/Abwr-/actions/runs/38145669297)，build/emulator 均 success |
| 產品下載 | [Abwr-signed-apk](https://github.com/Tsenjii/Abwr-/actions/runs/38145669297/artifacts/11704746467)，解壓後 `app-release.apk` |
| 版本 | `0.10.0` / code `19` |
| 產品 package | `dev.local.aibrowser` |
| 開發 package | `dev.local.aibrowser.dev`，不更新產品 |
| APK 大小 | 245,979,429 bytes |
| APK SHA-256 | `44ca30ffe322f4898093339617b83946a4a23553f295e49ad0614988d6cf44c8` |
| 憑證 SHA-256 | `c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`，與 v0.9 相同 |
| 實際模擬器證據 | [Abwr-emulator-evidence](https://github.com/Tsenjii/Abwr-/actions/runs/38145669297/artifacts/11705066370) |
| JVM XML | [Abwr-jvm-test-results](https://github.com/Tsenjii/Abwr-/actions/runs/38145669297/artifacts/11704621866) |

後續僅文件提交不改變上述已驗證程式碼或 APK。公開 artifact 依 GitHub 保存期限存在；下載後以 SHA-256 核對。

## 實際通過的檢查

Python 31 tests、四組 WebExtension JavaScript、Kotlin JVM 24 tests（failures/errors/skipped 都是 0），以及 Debug/Release Gradle 建置全部通過。Release 經 `apksigner verify` 並核對固定憑證；下載 APK 的 SHA-256 與 CI 輸出一致。

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

四個 PNG 已人工檢視：網址顯示 127.0.0.1、底部圖示置中、分頁使用中/數量清楚、中文標題完整、容器展示目前頁面。這不代替使用者對真機觀感與觸控的驗收。

本輪 logcat 與 `system-exit-info.txt` 沒有主程序死亡、Abwr ANR 或 fatal crash；背景 diagnostics uptime 約 135 秒，未重設。記錄中 Gecko 子程序是 `EXIT_SELF/status 0`。前一輪有連線中斷與疑似主程序重啟，但當時晚取日誌遺失了原因；調整模擬器 RAM 並加入全程日誌/PID 檢查後本輪通過，**不能據此斷言前次根因或長時間穩定性已解決**。

背景 capture 成功時 ImageReader swaps 仍是 0，因此不能再以沒有 swap callback 當作失敗。實際 `capturePixels()` 成功數、Surface 世代及像素才是證據。`/screenshot/meta` 的 `stale:true` 描述快取；本輪新 JPEG 來自 `/screenshot`，沒有使用 `/last` 冒充。

## 簽章尚待的最後一步

v0.9 原私鑰已從既有 CI cache 回收，核對憑證後另存私密備份。Release 缺 key 或憑證不符會直接失敗；產品 package 維持不變，開發版另用 `.dev`。

CI 38145669297 的簽章來源仍是舊 cache。永久 Actions Secret `ABWR_SIGNING_KEY_B64` 尚未設定；不能把 cache 當持久簽章。完成 Secret、驗證新 CI 走 repository secret 且憑證相符後，移除臨時 envelope export 與 cache fallback。詳見 [signing/README.md](../signing/README.md)。更早不同且已遺失的 APK 私鑰無法用 v0.9 key 代替；覆蓋安裝前須比較已安裝憑證。

## 下一輪真機驗收

請交本地 AI 執行 [v0.10 Prompt](V0_10_ACCEPTANCE_FIXES.md#本地-ai-重驗-prompt)。優先同簽章更新並保留資料、HTTP/MCP 各 50 輪、SHARP 背景/鎖屏 1/10/30 分鐘、日常 UI、人工接管、下載/上傳及普通登入流程。八小時常駐、12 分頁/10 容器、登入持久性等 V1 門檻仍未全部證明，見 [Release Gate](V1_RELEASE_GATE.md)。
