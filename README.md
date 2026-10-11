# Abwr — Android AI Browser Server

**Kotlin + GeckoView + WebExtension + 本機 HTTP API + MCP 橋接**

專用於受信任的本地 AI Agent 的 Android 瀏覽器服務。與你平常使用的 Chrome／Brave 網站資料分離；外部 Agent 可控制自己的瀏覽器工作階段。你可以打開 App 手動接管瀏覽器、登入自己持有的帳號，再交還 Agent。

## 目前發行狀態

**2026-10-11：Android Debug APK 已於 GitHub Actions 成功編譯並上傳 Artifact。Android 真機功能仍未完成測試。**

- [最新已成功編譯的工作流程 #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)
- 請登入 GitHub → 點連結 → **Artifacts** → **Abwr-debug-apk**，下載 ZIP 並解壓取得 `app-debug.apk`。
- 第一次安裝需在 Android 系統允許所使用的檔案管理器安裝 APK。此為**測試用 Debug 版**，尚未發行正式版。

## v0.3.1 背景能力（實驗）
- 前景服務現在依「由使用者啟動的本機瀏覽器 API」使用 `specialUse`，避免將此用途誤標為 `dataSync`；Android 仍可能因記憶體、使用者強制停止或 OEM 背景政策終止，不能保證永不被殺。
- App 內可選「背景截圖實驗模式」，嘗試用私有 VirtualDisplay 持續渲染 Abwr 網頁；**預設關閉，尚未真機驗證**。
- `/v1/screenshot` = 請求**即時**畫面；`/v1/screenshot/last` = 只拿快取**舊畫面**；`/v1/screenshot/meta` = 檢查快取拍攝時間。不可混淆。
- API 現支援 `/v1/command` 的 `navigate/back/forward/reload` 別名及 `/v1/profiles` CRUD 路徑。
- 請參閱 [背景工程與完整優先級](docs/BACKGROUND_AND_AGENT_ROADMAP.md)。

## 核心功能：原始碼及編譯狀態

| 功能 | 目前狀況 |
|---|---|
| Android GeckoView 瀏覽器 | 程式碼已編譯；待真機驗證 |
| GeckoSession profile contextId（與個人 Chrome/Brave 分開） | 已編譯；隔離效果待真機驗證 |
| 最多 300 個 profile metadata、最多 3 個開啟中的主要 Session | 已編譯；100 帳號尚未壓測 |
| HTTP loopback 服務 `127.0.0.1:8765`、Bearer Token | 已編譯；待真機 API 測試 |
| DOM/find/click/type/scroll/wait 等 WebExtension 工具 | JS 模擬測試通過；待實測 bridge |
| Python MCP stdio adapter | Python 協定測試通過 |
| 螢幕畫面截圖（有可見 GeckoView 時） | 已編譯；待真機驗證 |
| 手動接管及返回 Agent 操作 | 已編譯；待真機驗證 |
| popup 基礎處理 | 已編譯；待真機驗證 |
| 整頁截圖 | **未支援**（請使用可見畫面截圖） |
| 不顯示 App 的背景截圖 | **尚未完成** |
| 自訂 Proxy | **尚未完成**，API 回應不支援 |
| Google OAuth／Cloudflare 自動驗證 | **未驗證**；不承諾可繞過安全檢查 |

## 詳細文件

- [完整專案目標](docs/PROJECT_GOALS.md)
- [最新進度與每次 CI 排錯記錄](docs/PROGRESS.md)
- [AI／開發者交接文件](docs/HANDOFF.md)
- [架構](docs/ARCHITECTURE.md)
- [API 範例](docs/API.md)
- [測試計畫](docs/TEST_PLAN.md)
- [安全說明](docs/SECURITY.md)

## 使用方式（需先安裝 APK 並實測）

開啟 App → **啟動服務** → **複製 API Token**。服務預設僅在 Android 本機 `127.0.0.1:8765` 監聽。

```sh
curl http://127.0.0.1:8765/health
export AI_BROWSER_TOKEN="貼上從 App 複製的金鑰"
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" \
  http://127.0.0.1:8765/v1/status
```

若本機 AI Agent 有 MCP stdio 支援，可以設定 `python3 bridge/mcp_stdio.py`，並透過環境變數提供 `AI_BROWSER_TOKEN`。

**注意**：不同 Android App 對回環網路連線可能有不同限制。若從電腦測試，可透過 ADB `adb forward tcp:8765 tcp:8765`。不可把本機 API 直接暴露到公網。

## 編譯

CI 使用 AGP 9.1.1、Gradle 9.3.1、JDK 17、Android API 37.0、Kotlin Gradle Plugin 2.4.20、GeckoView 157。GitHub Actions 會跑 Python/JS 測試，接著 `gradle :app:assembleDebug --stacktrace` 並上傳 APK。該工作流程已成功完成一次；本專案不包含 Gradle wrapper JAR，使用 CI 提供的 Gradle。

**限制**：可以編譯不等於能穩定登入 Google。Google 可能拒絕內嵌瀏覽器 OAuth；Cloudflare 需要遵守網站提供的正常驗證程序。本專案不提供繞過驗證、安全措施或帳號限制的功能。
