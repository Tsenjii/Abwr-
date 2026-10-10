# Abwr — Android AI Browser Server

> Android 專用的獨立 AI 瀏覽器服務。由本機 Agent 經過授權的 HTTP / MCP 介面進行操作，使用者可隨時開啟瀏覽器畫面接手。

## 專案狀態
目前屬於 **開發測試版**；GitHub Actions 的執行結果才是編譯真實狀態。尚未完成 Android 真機及 Google OAuth 相容性驗證。

## 核心目標
- 獨立 GeckoView 瀏覽器：不使用個人 Chrome／Brave 的 Cookie。
- 以 profile 分隔網站資料，按需載入；目標 100+ 個既有帳號。
- 外部 Agent 使用 HTTP API 或 MCP stdio bridge 存取 DOM、點擊、輸入、截圖與分頁。
- 人工接管與一鍵交回 AI；Agent 不應干擾使用者正在使用的手機介面。
- 本機 token 驗證，預設只在 127.0.0.1 監聽。
- Proxy 為選配功能，必須實測後才可宣稱支援。

**限制：** Google 可能拒絕嵌入式瀏覽器 OAuth；不提供 Cloudflare / CAPTCHA 的繞過能力，必要驗證由使用者自行完成。

請看 [專案目標](docs/PROJECT_GOALS.md)、[進度](docs/PROGRESS.md)、[交接](docs/HANDOFF.md) 和 [API 文件](docs/API.md)。

## 自動編譯
進入 GitHub → Actions → **Build Android APK**；成功後在對應執行紀錄的 Artifacts 下載 debug APK。尚未執行成功的工作流程不等於已有 APK。

## 安全
只能連線受信任的 Agent；不要把本機連線 token、Cookie 或 OAuth 憑證提交到 Git。