> **2026-10-14 最新驗收修正：** [精確閒置與產品交接](V0_14_1_IDLE_RESULT.md)。v0.14.1 固定簽章產品已發布並通過兩個 Android 系統的覆蓋更新；舊 1／10／30 分鐘資料是累計檢查點。PR #7 已通過兩個 Android 系統各 22 組與獨立 60／600／1800 秒閒置，本次出口 IP 的回歸測試亦通過，發布須保留各來源的證據。

# v0.14.0 建置與產品驗收紀錄

2026-10-13，Asia/Taipei。**SOURCE 與 CI 產品驗收通過；SHARP R8s DEVICE 未測，仍非正式 V1。**

## 下載與版本

[固定簽章產品 APK ZIP](https://github.com/Tsenjii/Abwr-/actions/runs/38386576654/artifacts/11806422460)。解壓 app-release.apk 後在 Android 覆蓋安裝；不先卸載或清資料。Abwr-dev-apk 是獨立測試 package，不代替產品更新。Actions artifact 保留期以 GitHub 為準。

| 項目 | 實際核對值 |
|---|---|
| 版本／code | 0.14.0／23 |
| Product package | dev.local.aibrowser |
| App/API/contentBridge/MCP/SDK | 0.14.0 |
| Schema／MCP tools | 4／82 |
| 產品 sourceRevision | 8b86c094775f7911a4e707cb1a10abf90c358eae |
| 合併 PR | https://github.com/Tsenjii/Abwr-/pull/4 ＋ https://github.com/Tsenjii/Abwr-/pull/5 |
| CI | https://github.com/Tsenjii/Abwr-/actions/runs/38386576654 |
| Signed artifact | 11806422460 |
| APK bytes | 246172280 |
| APK SHA-256 | 8c066ab055e3597995b1f3679b97f433a29517b79981ab6748b6c2b1b2bf3cf9 |
| 固定憑證 SHA-256 | c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a |

APK 已下載並核對本文 SHA-256、內嵌 sourceRevision、assets bridge manifest 版本，以及 CI apksigner 結果；CI 模擬器已安裝產品的 diagnostics 亦核對來源與憑證。後續純文件提交不改變產品程式碼，APK sourceRevision 不必等於純文件 head。

## 本版 CI

Python 61／JavaScript 六組／Kotlin JVM 63 全部 PASS；Android Debug 與原固定簽章 Release 均成功。Main 直接安裝不可 debug 的產品 APK，22 組 PASS、0 FAIL、0 NOT_RUN；證據 artifact 11810970459。

[repo 內可機器讀取的驗收摘要](evidence/V0_14_CI.json) 保存同一產品的來源、雜湊、每項結果、循環數與實際 checkpoint。完整圖像／UI／logcat 仍以該 Actions artifact 為準。

- HTTP 與實際 MCP 各 100 輪、接管 20 輪、分頁／群組／重啟保存、首次文件與像素隔離。
- 系統 Settings Activity 往返保留 documentId；native focus 恢復前 viewHasFocus:false/windowHasFocus:true，恢復後三鍵 down/up handled 與 abc 實際 DOM；原生逐鍵及 timed touch 的實際頁面結果；共享 prompt／file 真實內容與檔名／網站權限／HTTP auth。
- Gecko response 事件與 network_idle／文件變更／frame 就緒／WAIT_TIMEOUT；allowlist 預設關閉、開啟後 geometry；links 分頁。
- Storage export 精確布林 consent、scope 與 stale 拒絕；一般值保留、local/session token 值遮蔽、metadata audit。
- 15 個隔離 fixture profiles 循序維持 bridge Ready；不宣稱 15 路同時運行或 15 個不同 IP。
- A/B 兩個實際 HTTP 上游、SDK 本文 SHA＋Gecko 事件健康檢查、新 tab／direct profile、HTTPS CONNECT＋Gecko 200、SOCKS auth、失效 HTTP_NETWORK_ERROR 不直連。
- 腳本、DevTools、擴充、無痕／原生拖曳／全螢幕、閱讀／5 MiB authenticated download／PDF／HTML 書籤。

## 鎖屏與背景證據

時間以同一輪閒置開始計算，沒有持續 API 輪詢維持存活。每個 checkpoint 使用新 URL／documentId、核對實際綠／藍像素、JPEG metadata、screenInteractive:false、背景 display 及相同 PID；30 分鐘後回前景再核對畫面。

長測每次直接讀 `/v1/screenshot` 的 live JPEG，再讀 `/meta` 核對剛拍攝的文件與時間；`/meta` 是 cache 描述，stale:true 是刻意標記，不代表 live JPEG 被替換為舊圖。自動化原語另直接驗證原子 `/evidence` 的 metadata.stale:false 與同次 JPEG。

| Checkpoint | 實際 elapsed | PID | 結果 |
|---|---:|---|---|
| 1 分鐘 | 60.6 秒 | 6739 | 新文件／新 JPEG、鎖屏 PASS |
| 10 分鐘 | 600.5 秒 | 6739 | 新文件／新 JPEG、鎖屏 PASS |
| 30 分鐘 | 1800.5 秒 | 6739 | 新文件／新 JPEG、鎖屏 PASS |

本版 native crash buffer：空白，沒有記錄到 native crash。這些是 Android 35／Pixel 2 模擬器證據，不代表 SHARP／OEM／各網站同樣通過，也不能宣稱所有 Gecko SIGSEGV 根因已永久消失。

## 手機接續

[真機驗收提示詞](V0_14_DEVICE_ACCEPTANCE.md)、[Termux／HTTP／MCP 契約](V0_14_AUTOMATION.md)、[下一個 AI 的目標與進度](AI_HANDOFF_2026-10-13.md)。尚需 SHARP 覆蓋更新保留資料、前景／背景／鎖屏 1/10/30 分鐘、低記憶體／無 Activity 恢復、真實網路環境與逐站 OAuth／Passkey／DRM；沒有證據即 NOT_RUN。
