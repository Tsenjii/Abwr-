# Abwr AI 工程交接文件（2026-10-11）

Repo：https://github.com/Tsenjii/Abwr-

## 最重要的現況
- **已成功編譯 Debug APK**：[GitHub Actions #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)，Artifact 名 `Abwr-debug-apk`。
- **部分真機驗證成功（2026-10-11）**：Android 可安裝／顯示網頁；/health、Bearer Token /v1/status、G001 /v1/command action=dom 都已由使用者提供 Termux 實測結果。`ready:true`、`extensionPortReady:true`，成功取得 Example Domain 標題、正文、IANA 連結與元素位置。詳見 docs/PROGRESS.md。
- **未完成的真機驗證**：click/type/scroll、畫面截圖、人工接管、profile 儲存隔離、MCP tool calls、OAuth、多帳號壓測。不要告知使用者「全部功能可以用了」。
- Android GeckoView/Kotlin 本機 Agent Browser Server，非一般聊天 App。獨立於 Chrome/Brave 工作階段。
- 重要未完成：真正背景截圖、全頁截圖、原生 Proxy、Google OAuth相容性、100+ 持久登入壓測。
- 使用者希望能在同一個 AI Session 人工接管登入並交還 Agent；HTTP/MCP 不控制自己的 Chrome/Brave UI。

## 專案布局
- `app/src/main/java/dev/local/aibrowser/BrowserController.kt`：GeckoRuntime、contextId、session、native messaging、命令、螢幕截圖。
- `app/src/main/java/dev/local/aibrowser/LocalApiServer.kt`：127.0.0.1:8765 + bearer token。
- `app/src/main/java/dev/local/aibrowser/MainActivity.kt`：人工作業／多帳號管理。
- `app/src/main/java/dev/local/aibrowser/BrowserService.kt`：Android foreground service。
- `app/src/main/assets/agentbridge/`：WebExtension 內容腳本、DOM 指令。
- `bridge/mcp_stdio.py`：MCP stdio 協定橋接。
- `docs/API.md`：端點說明。
- `.github/workflows/build-apk.yml`：GitHub Actions 建置和 artifact。

## 下一步（按優先度）
1. **已通過：** APK 安裝、GeckoView example.org、/health、Bearer Token /v1/status、WebExtension action=dom。
2. **下一步：** 利用普通測試網站驗證 WebExtension click/type/scroll、等待元素、一般頁面截圖。
3. **再來：** 人工接管與交還 Agent，確認同 Session 不搶操作。
4. 驗證兩個 contextId 的登入資料與 Cookie 狀態確實隔離；再測 MCP stdio 真實 tool calls。
5. 測試從網站的 OAuth popup 及回跳是否會失敗；Google 可能拒絕內嵌登入，不可繞過其安全限制。
6. 修正背景渲染與 screenshot；再做可選代理配置。
7. 後續測試 10/30/100 個 profiles 的 RAM／效能與儲存穩定性。
8. 每次改版更新 docs/PROGRESS.md、docs/HANDOFF.md、CHANGELOG.md。

## 建置版本
AGP 9.1.1 + Gradle 9.3.1 + Kotlin Gradle Plugin 2.4.20 + compileSdk 37.0（Kotlin DSL `release(37)`）、JDK17、GeckoView 157。CI 連續排錯記錄見 PROGRESS.md。

**注意**：GeckoView 的 `captureFullPage()` 並不存在於目前編譯使用 API，原本的 full-page handler 已明確回報不支援；只有可見畫面 `capturePixels()` 編譯通過且仍需實測。Proxy HTTP endpoint 未實作，不能宣稱成功啟用。不要憑網站表示的「已登入」推測與 Google OAuth 相容。
