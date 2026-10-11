# Abwr 專案進度 — 2026-10-11

## 已達成的里程碑

**CI PASS — Debug APK 已成功編譯並上傳。**

- Repo：[Tsenjii/Abwr-](https://github.com/Tsenjii/Abwr-)
- 成功 CI：[Build Android APK #38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)
- 最後成功編譯的程式碼 Commit：`4b0ad43f34c667b9cc95d30dd86a47c9e5c8ec51`
- Artifact：`Abwr-debug-apk`（已確認存在且未過期；內含 APK）
- CI 步驟：SDK、Gradle、Python、Node、Android build、APK upload 全部 success
- 不代表 Android 真機、Google OAuth、多帳號壓力測試已通過。

## 真機驗收回報（2026-10-11）

使用者回報「都行了」，對應上一輪明確指定的兩項驗收：
1. **Device PASS — App 啟動／GeckoView**：Debug APK 安裝及開啟成功，`https://example.org/` 可以顯示。
2. **Device PASS — Local HTTP /health**：在同一台 Android 手機呼叫 `http://127.0.0.1:8765/health`，取得正常 `ok: true`。

**證據範圍**：使用者回報，未取得 logcat 或影片。尚未驗證 Bearer Token 認證後的 `/v1/status`、WebExtension DOM 通道、截圖、MCP、Cookie 隔離、Google OAuth 與背景瀏覽能力。不要把 /health PASS 誤寫成完整 API PASS。

## CI 排錯軌跡（提供給下一位 AI）
1. [#38077756568](https://github.com/Tsenjii/Abwr-/actions/runs/38077756568)：`android-actions/setup-android@v3` 安裝不存在的 `tools` SDK package。
2. [#38077838078](https://github.com/Tsenjii/Abwr-/actions/runs/38077838078)：GeckoView 157 / AndroidX Core 1.19 要求 API37、AGP >=9.1。
3. [#38078020438](https://github.com/Tsenjii/Abwr-/actions/runs/38078020438)：SDK 套件名稱由 `android-37` 更正為 `android-37.0`，`compileSdk { version = release(37) }`。
4. [#38078117398](https://github.com/Tsenjii/Abwr-/actions/runs/38078117398)：Kotlin 編譯器 2.2 無法讀 2.4 metadata；使用 KGP 2.4.20。
5. [#38078350384](https://github.com/Tsenjii/Abwr-/actions/runs/38078350384)：剩 3 個 Kotlin 錯誤：nullable Throwable、GeckoView 無 `captureFullPage()`、可空 Bitmap。
6. [#38078531735](https://github.com/Tsenjii/Abwr-/actions/runs/38078531735)：**編譯及 Artifact 上傳全部成功。**

## 功能成熟度

| 功能 | Source | CI | Device |
|---|---|---|---|
| GeckoView Android 瀏覽器 | 已寫 | PASS | **PASS：使用者確認可載入 example.org** |
| contextId Cookie/storage 隔離 | 已寫 | PASS | 未測 |
| profile 建立、切換、持久 metadata | 已寫 | PASS | 未測 |
| HTTP API、token | 已寫 | PASS | **部分 PASS：/health 正常；Bearer Token 未測** |
| MCP stdio bridge | 已寫 | PASS + Python 測試 | 未測 |
| DOM／click／type／scroll | 已寫 | PASS + JS 模擬測試 | 未測 |
| 人工接管、popup | 已寫 | PASS | 未測 |
| 可見畫面截圖 | 已寫 | PASS | 未測 |
| 全頁截圖 | 尚未完成 | 不適用 | 未測 |
| 背景無畫面截圖 | 尚未完成 | 不適用 | 未測 |
| 原生 Proxy | 尚未完成 | 不適用 | 未測 |
| Google OAuth／CF 挑戰 | 依網站正常流程 | 不適用 | 未測 |
| 100+ Google 登入狀態保持 | 僅 metadata | 不適用 | 未測 |

## 下一步
已通過 Android 真機 App 啟動、一般網站載入及 /health。**下一步順序**：帶 Token 呼叫 /v1/status → /v1/command action=dom → 以簡單網站測試 click/type → 畫面截圖 → 人工接管與容器隔離；之後才測 popup、100 Profile 壓力測試與 Proxy。逐項回填 Device PASS/FAIL。
