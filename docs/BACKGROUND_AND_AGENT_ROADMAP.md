# Abwr v0.3 — 背景維護與 Agent API 修復清單

更新：2026-10-11。來源：使用者本機 AI 真實驗收（iana.org→icann.org、頁面搜尋、畫面截圖）。

## 技術事實與限制

- **已確認原因 1**：舊版 BrowserService 把常駐瀏覽器視為 `dataSync` 前景服務。Android 15+ 對該類別限制 24 小時內背景最多累計六小時，與常駐瀏覽器用途不符。新版本以 `specialUse` 申告「由使用者啟動、持續在本機提供瀏覽器控制服務」，附 Manifest subtype 理由；仍需遵守 Android 及商店審核規範。
- **已確認原因 2**：GeckoView 的 `capturePixels()` 需有效的 compositor / Surface。Activity 後台後 Surface 可能銷毀，HTTP 無法強制在沒有視窗的情況拍攝真正的頁面。新版本提供明確分開的 **live vs cached** API；絕不把過期快取冒充即時截圖。
- Android 仍可能因使用者強制停止、系統電池最佳化、OEM 背景政策與低記憶體回收而停止進程，並非改前景服務類型就能保證 24/7。新 App 設有直接打開系統 App 管理頁的按鈕，讓使用者自行選擇背景／電池設定。
- 不會自動將 App 強行切到前景遮住使用者畫面，也不會利用 Accessibility 假裝真人或干擾使用者其他 App。
- Google OAuth／Passkey／站點人機驗證沒有真機測試完成；只做狀態偵測、必要時協助使用者正常完成驗證，不會偽裝真人或繞過站點驗證。

## v0.3 實作（已寫程式碼，CI 與真機尚待驗證）
- 常駐服務改用 `specialUse` + 可見通知，保留 `START_STICKY`（僅系統允許時最佳努力重啟）。
- App 加「背景執行設定（系統）」入口，不默默變更 Android 電池政策。
- `GET /v1/screenshot/meta`：回傳上次成功拍照時間、快取年齡和是否具備即時背景截圖。
- `GET /v1/screenshot/last`：明確取**舊畫面**（沒拍過回 409），與 `GET /v1/screenshot` 即時截圖分開。
- `POST /v1/command` 現在接受 `navigate/goto/open/load/back/forward/reload`；原生導航有獨立路由時仍相容。
- `POST /v1/profiles` 與 `POST /v1/profiles/{create,switch,delete}` 作為既有單數 API 的別名（原單數 API 保留）。
- WebExtension `find` 可選 CSS、text、role、ariaLabel 篩選；`clear`、`toggle`、`keyboard`（僅合成 JS 鍵盤事件）、`scrollTo`。
- `status` 增加 title、loading、`httpStatusAvailable:false`、`cachedScreenshotAvailable`、`liveBackgroundScreenshotSupported:false`。
- 原有 `wait {selector,timeoutMs}`、`select {selector,value}`、`/v1/tabs`、`/v1/tabs/close_popup` 在 v0.2 就存在，現在同步修正文件。

## 下一階段最高優先級（未完成）
1. **真正背景渲染與即時截圖**：評估 GeckoDisplay + 合法持續有效的 Surface（例如專用 VirtualDisplay 或使用者可見的畫面）是否能在 App 不可見時渲染，必須在 Android 13、14、15 實測，不能推測。避免偷畫或隱藏 overlay 影響他人 App。
2. **長時間常駐壓測**：屏幕鎖定／切 App／Android Doze／低記憶體壓力／系統重啟，記錄服務生存時間、錯誤碼、截圖新鮮度；清楚區分「關畫面」與「強制停止 App」。
3. **導航與等待**：加 `waitForNavigation`、可靠 title、loading、navigation errors；HTTP status 暫不可用，不能偽造。
4. **自動化輔助**：下載事件、一般表單、keyboard 真實輸入、分頁管理、截圖品質等。
5. **profile 真隔離及持久性**：至少 2 profile 的 Cookie/Storage 實測，再測 10/30/100 metadata + Session LRU。
6. **驗證/登入正常流程**：只做 challenge 出現與否辨識、通知、人工接管／交還；Google 登入相容性不保證。絕不提供人機驗證、IP 風控或身份檢查規避。
7. **安全**：API Token 預設啟用、僅 127.0.0.1，未信任網頁的腳本/內容不可變成 Agent 系統指令。

## 正確的 API 範例
- `POST /v1/command {"action":"navigate","url":"https://example.org/","profile":"G001"}`
- `POST /v1/command {"action":"find","selector":"input","text":"搜尋","profile":"G001"}`
- `POST /v1/command {"action":"clear","selector":"input[name=q]","profile":"G001"}`
- `POST /v1/command {"action":"keyboard","key":"Enter","profile":"G001"}`
- `GET /v1/screenshot/meta`：有拍攝時間戳
- `GET /v1/screenshot/last`：**永遠是快取**；無法證明現在頁面狀態

## 驗收記錄原則
SOURCE：已寫程式碼；CI PASS：真正 GitHub Actions APK build；DEVICE PASS：真機跑過具體 API 及行為。三者不能混淆。
