# 專案進度（2026-10-11）

**最新基線：v0.2-source，尚未確認 Android APK 編譯成功。** 此文件優先於口頭宣稱。

| 功能 | 狀態 | 證據／限制 |
|---|---|---|
| Android Kotlin / GeckoView | 原始碼 | GitHub Actions build 待確認 |
| Gecko contextId 容器 | 原始碼 | 100 個未實測 |
| WebExtension DOM 工具 | 原始碼／JS 模擬通過 | Gecko 真機未驗 |
| HTTP 127.0.0.1 API + token | 原始碼 | 真機未驗 |
| MCP stdio bridge | 原始碼 | 本機 protocol mock 測試 |
| AI 遠端命令 profile 指定 | 原始碼 | 當前選取 profile 不應被更改 |
| 手動接管 / popup | 原始碼 | Android 真機未驗 |
| 可見介面截圖 | 原始碼 | 背景無 Surface 時不可保證 |
| Proxy 配置 | 未完成 | API 明確回 501 |
| Android 編譯 | 未確認 | 以 Actions 實際 log 為準 |
| Google OAuth | 未驗證 | Google 可能拒絕嵌入式登入 |
| Cloudflare 驗證 | 未驗證 | 不提供繞過方案 |

## 下一步
1. GitHub Actions 編譯並保留真正 APK artifact。
2. 修正 Kotlin 與 Gecko API 編譯錯誤（如有）。
3. Android 13 真機測試及畫面截圖。
4. Background rendering / proxy 可選配置。
5. 大量帳號效能驗證。

### 狀態詞彙
SOURCE：已寫程式碼；CI PASS：GitHub Actions 真實建置成功；DEVICE PASS：Android 真機實測成功。
任一 SOURCE 不得自動升級為 DEVICE PASS。
