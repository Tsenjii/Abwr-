> **2026-10-14 最新已驗證來源：** [完整閒置與產品交接](V0_14_1_IDLE_RESULT.md)。PR #7 已批准合併 main；固定簽章 CI 38441974654 SUCCESS，source `519d82f`，Python 71／JS 六組／JVM 63。Android 13／15 各 22 PASS、保留資料升級與獨立 60／600／1800 秒閒置 PASS。原簽章保留；既有 v0.14.1 release 資產保持原來源。SHARP 真機／15 真實出口 NOT_RUN，非正式 V1。

# v0.14.1 建置與驗收紀錄

產品程式、Debug／固定簽章 Release、雙 Android CI 與發布均已完成。SHARP R8s 真機與 15 個實際獨立出口仍為 **NOT_RUN**；不能把此版稱為已通過真機的正式 V1。

## PR #7 合併後產品（最新已驗證來源）

[完整 CI 38441974654](https://github.com/Tsenjii/Abwr-/actions/runs/38441974654) SUCCESS；實際 APK sourceRevision `519d82f6db24f6b20d5fb7467b16201c20915be6`，[本次固定簽章 APK](https://github.com/Tsenjii/Abwr-/actions/runs/38441974654/artifacts/11837510118)，246,173,751 bytes，SHA-256 `019edbf0b586379b923f77e83f29c1ede35090108c417b7c03fe6191145a0e23`。App 0.14.1/code 24，原固定憑證未變。

Python 71、JS 六組、JVM 63、Debug／Release PASS；Android 13／15 各 22 PASS，原簽章保留資料升級 PASS。每輪連續閒置 60／600／1800 秒，三輪結束累計 2461.7／2461.6 秒；同程序身分、新文件／JPEG、實際像素 PASS。HTTP／MCP 各 100 輪，native crash buffer=0。來源、artifact digest／PID／升級資料：[本次完整結果](V0_14_1_IDLE_RESULT.md)／[機器證據](evidence/V0_14_1_MAIN_IDLE_CI.json)。

既有 v0.14.1 release 的 APK 與六項資產保持原來源；本次結果不覆寫下方首次發布的證據。

## 初次發布產品與下載（保留原來源）

- App／API／contentBridge／MCP／SDK：0.14.1；versionCode 24、schema 4、82 tools。
- 已驗程式 sourceRevision：`e20ffc97559a2857acb5e5bd64b0d8b1b0f3f153`。後續交接文件提交不改此 APK。
- [完整 CI 38426201545](https://github.com/Tsenjii/Abwr-/actions/runs/38426201545)：SUCCESS。
- [固定簽章 APK](https://github.com/Tsenjii/Abwr-/releases/download/v0.14.1/app-release.apk)，246173839 bytes；[候選版發布頁](https://github.com/Tsenjii/Abwr-/releases/tag/v0.14.1)。
- APK SHA-256：`e419b4ed3f64bfd35c5682c9680bb44b1fd01267b5f1064766b59afec6cf39c6`。
- 原固定憑證 SHA-256：`c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`。未生成替代 key。
- [機器可讀完整摘要](https://github.com/Tsenjii/Abwr-/releases/download/v0.14.1/acceptance-summary.json)／[原始證據包](https://github.com/Tsenjii/Abwr-/releases/download/v0.14.1/Abwr-emulator-evidence.zip)。發布資產的 APK digest／尺寸與 CI 計算值一致，發布 target 為同一 source。

## 初次發布實際驗收（歷史）

Python **68**、JS **六組**、Kotlin JVM **63**（0 failure/error/skipped），Debug／Release／原憑證核對均 PASS。build job 115396125950；API33 job 115397921467；API35 job 115397921517；release job 115420755410。

| Android API | PASS / FAIL / NOT_RUN | HTTP / MCP 輪數 | 鎖屏累計秒數：第 1 / 10 / 30 分鐘 | 鎖屏三點 PID | native crash buffer bytes |
|---|---|---|---|---|---|
| 33 | 22 / 0 / 0 | 100 / 100 | 60.5 / 600.5 / 1800.5 | 10526 | 0 |
| 35 | 22 / 0 / 0 | 100 / 100 | 60.6 / 600.5 / 1800.5 | 7575 | 0 |

秒數是同一背景測試起點的累計 elapsedSeconds。三個 checkpoint 的 documentId、capturedAtMs 與 JPEG SHA 均不同，螢幕關閉且背景 renderer active；Pillow 實測綠／藍像素。回前景也實際核對新 JPEG。HTTP／MCP 各 100 輪同 PID：API33 5898、API35 4802。私密資料不落盤測試中有**刻意重啟**，故長測 PID 改為上表數值；不能把不同測試階段的 PID 說成從頭到尾相同。

兩個環境各有 22 組全套 PASS，另有固定簽章覆蓋升級 PASS。15 個 fixture profiles 循序隔離；一／二路唯讀工作各十輪，maximumReadConcurrencyProven=2、writeConcurrencyProven=null。兩個私密 profile 由實際原生 context 路由首次請求、新 tab 與相同 URL；上游停止後無直接出口 fallback。HTTP／HTTPS／SOCKS、TLS、已知本文與 Gecko response 也已驗證；此為受控 CI 出口，沒有宣稱 15 個真實 IP。

## 保留資料升級與失敗歷史

[已驗 v0.14.0 前版](https://github.com/Tsenjii/Abwr-/releases/tag/v0.14.0) 的 APK SHA 固定為 `8c066ab055e3597995b1f3679b97f433a29517b79981ab6748b6c2b1b2bf3cf9`。僅在 fresh rooted QEMU 上先植入 A/B fixture，透過 ABWR 事件泵等 20 秒後，**舊版本身先重啟讀回 green**，再以 `adb install -r` 更新。更新後只讀取證據，不重新植入 storage。兩個 API 都證明原憑證、Token、context IDs、tabs、bookmarks、隔離的持久 Cookie、localStorage 與 manual takeover 保留；dataCleared=false，profile prefs hash unchanged=true。

前輪「立即 force-stop 後讀 null」保留於 production attempts 2/3；落盤等待後的舊版重啟與正式升級均成功，與 Gecko 延遲刷寫相符，**不等於保證剛寫完就 force-stop 仍不丟資料**。原 API35 startup timeout 未再現，沒有憑空指定 native root cause。v0.11／v0.13 的既有原生崩潰歷史仍保留；本輪 crash buffer 為空不代表所有情境永不崩潰。

完整來源、artifact IDs／ZIP digest、release asset digest、每組結果與 checkpoint：[V0_14_1_CI.json](evidence/V0_14_1_CI.json)。前輪失敗：[production 1](evidence/V0_14_1_PRODUCTION_ATTEMPT1.json)、[production 2](evidence/V0_14_1_PRODUCTION_ATTEMPT2.json)、[production 3](evidence/V0_14_1_PRODUCTION_ATTEMPT3.json)、[PR attempt 1](evidence/V0_14_1_ATTEMPT1.json)、[PR attempt 2](evidence/V0_14_1_ATTEMPT2.json)。

## 下一步與範圍

G1–G20 的通用原語、HTTP／MCP 對照、參數／錯誤契約、Termux SDK／槽位閘道／fixture 已交付；AI／UI 共用一般權限，顯式 takeover 仍可暫停並由已授權 API 交還。原始 Cookie／密碼／權杖匯出、驗證解算、指紋偽裝／識別輪替、永久背景存活、force-stop 後保證執行仍是非目標。

具裝置存取能力的下一個 AI 依 [手機單機證據指南](V0_14_1_TERMUX_EVIDENCE.md) 覆蓋安裝、同步 bridge 並實際跑 foreground／background／locked 1/10/30 分鐘與 15 授權上游出口。保留原帳號／容器，不卸載或清資料。無手機與出口設定就維持 NOT_RUN；第三方 OAuth／Passkey／DRM 仍需各自授權情境，不能從 fixture 推論成功。
