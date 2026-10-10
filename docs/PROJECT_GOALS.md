# Abwr 完整專案目標

## 核心任務
開發 Android 裝置上的專用 AI 瀏覽器服務。與手機使用者自己的 Chrome／Brave 登入資料分離，允許受信任本地 AI Agent 經 API 操作一般網頁，使用者亦可在 App 畫面手動接管。

## 第一階段：可編譯、可操作
- Kotlin Android 專案與 GeckoView 瀏覽器引擎
- GeckoSession 依 contextId 區隔網站資料、按需啟動
- HTTP Loopback 服務：127.0.0.1:8765 + token
- Agent 可導航、取得 DOM 與連結、尋找 CSS 選取器、點擊、輸入、捲動、等待元素
- 瀏覽器可見畫面截圖與手動接管
- 儲存工作階段狀態、API 文件、真實測試紀錄

## 第二階段：穩定性、使用體驗
- Android 背景渲染及截圖可用性驗證
- 分頁與彈窗／回呼生命週期
- 網頁檔案上傳下載、站點授權與密碼安全
- 網路 Proxy 選擇（只有實作並實測後才宣稱支援）
- 10／30／100+ 帳號 metadata 及實際登入工作階段壓力測試

## 不承諾之事
不能保證 Gmail／Google OAuth 接受內嵌瀏覽器；不能保證任意網站反自動化系統放行。Cloudflare 驗證由網站正常處理，必要時由使用者接管。

## 驗收門檻
1. GitHub Actions assembleDebug 成功、提供可下載 APK。
2. Android 真機啟動、分頁、DOM 指令、截圖與接管各有測試證據。
3. 不影響 Chrome／Brave 的原有登入狀態。
4. API 認證與本機監聽正確。
5. 每個版本更新 PROGRESS、HANDOFF、CHANGELOG。
