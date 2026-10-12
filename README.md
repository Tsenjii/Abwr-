# Abwr — Android 瀏覽器與本機 AI 控制

Abwr 是 Kotlin／GeckoView 瀏覽器。你可以正常開網頁、管理分頁、登入自己的帳號，再把同一個瀏覽空間交給本機 AI；需要時按「人工接管」暫停 AI 寫入。

目前工程候選版本 **0.13.0／code 22**，MCP **68 個工具**。Python 37、JS 六組、Kotlin JVM 55、Android Debug／Release 與固定簽章通過；實際產品 APK 的 19 組驗收全部 PASS，包含 HTTP／MCP 各 100 輪及 1／10／30 分鐘關螢幕新文件／JPEG。SHARP 真機尚未重驗，版本仍維持 0.x。[本版功能與實際驗證結果](docs/V0_13_BROWSER_FUNCTIONS.md)。

## 瀏覽器功能

- 底部網址搜尋、首頁、前後頁、停止載入／重新整理、桌面網站、頁面內尋找。
- 真實多分頁、雙欄真縮圖、搜尋、多選、持久分頁群組與關閉分頁復原。
- 分開保存 Cookie／Storage 的瀏覽容器；一般容器重啟恢復、無痕容器不保存瀏覽資料。
- 書籤與歷史搜尋、清除歷史、Firefox／Chrome HTML 書籤匯入匯出。
- 閱讀檢視、文字比例、手指縮放、網站資訊與權限、相機與麥克風授權、媒體全螢幕。
- Android 檔案上傳、下載管理、分享、PDF／JPEG／TXT 另存、主畫面捷徑、預設瀏覽器角色。
- Mozilla 擴充商店、簽章安裝、權限確認、啟停更新移除、popup／options；隔離使用者腳本編輯與匯入。
- 原生 Console／Network／DOM／Storage，以及具有請求本文、二進位與增量串流的 HTTP 工具。

各功能入口、容量與限制請讀[完整功能表](docs/V0_13_BROWSER_FUNCTIONS.md#功能入口)。GeckoView 不包含完整桌面 Firefox／Chrome 功能：Firefox Sync／Chrome 同步、自建密碼庫、完整 CDP、全頁長截圖及每容器 Proxy 仍未提供。

## 安裝與更新

產品更新使用 GitHub Actions 的 **Abwr-signed-apk**，下載 ZIP、解壓 `app-release.apk` 後在 Android 點選覆蓋安裝。先核對現有產品簽章；不先卸載或清資料。[APK 連結與核對資料](docs/V0_13_BROWSER_FUNCTIONS.md#更新與開啟)。真機同簽章覆蓋更新與資料保留仍須實測。

**Abwr-dev-apk** 是獨立 `dev.local.aibrowser.dev` 測試 App，無法代替原產品更新。產品使用固定憑證；CI 缺少永久簽章金鑰或憑證不符會停止 release。

## 接上本機 AI

開啟 Abwr，在「設定 → AI 連線」確認服務已啟動並複製 Token。API 只監聽 Android 本機 `127.0.0.1:8765`。把 Token 留在自己的 Termux 環境，不貼到對話或 Git。

```sh
export AI_BROWSER_TOKEN="從 App 複製的 Token"
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" \
  http://127.0.0.1:8765/v1/status
python3 bridge/mcp_stdio.py
```

將 `python3` 與 `bridge/mcp_stdio.py` 的絕對路徑設為 MCP stdio 命令，以環境變數提供 Token；OpenCode、Codex、Claude 或其他 MCP 客戶端均可使用。電腦可以透過 `adb forward tcp:8765 tcp:8765` 連線。[API 與參數](docs/API.md) · [跨 Agent 瀏覽器 Skill](skills/abwr-browser/SKILL.md)。

AI 有 DOM 快照、跨來源 iframe、原生鍵盤／觸控／拖曳、真實 JPEG、容器與分頁管理、HTTP 與串流。人工接管時讀取可繼續，寫入會被拒絕；跨文件與失效 snapshot ref 也會明確回錯，不自行重送提交。

## 驗證與開發

[工程進度](docs/PROGRESS.md) · [交接](docs/HANDOFF.md) · [V1 驗收條件](docs/V1_RELEASE_GATE.md) · [歷史變更](CHANGELOG.md)。

CI 使用 JDK 17、Gradle 9.3.1、Android API 37.0、GeckoView 157，先跑 Python／JS／JVM 測試與 APK 編譯，再用 Android 35 模擬器驗收 HTTP／MCP 各 100 輪、容器隔離、首次截圖、原生 UI、腳本、擴充及無痕。main 直接測試固定簽章、不可 debug 的產品 APK，再跑 1／10／30 分鐘關螢幕背景操作。

SOURCE 是已寫入程式碼，CI 是受控環境通過，DEVICE 是實際手機證據。Google／Microsoft OAuth、Passkey、DRM 及 SHARP 省電策略仍需真機結果；API 存活不等於畫面新鮮。`/v1/screenshot/last` 是舊快取，不能當即時截圖。
