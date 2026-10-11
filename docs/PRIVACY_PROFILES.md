# Abwr 容器隔離與指紋防護（v0.6.2）

## 定義

- **容器（profile）**：`G001`、`G002` 等使用不同 GeckoView `contextId`，Cookies、localStorage 等網站儲存資料互不共享；同容器多分頁共享該容器的登入狀態。
- **指紋（browser/device fingerprint）**：由網頁可觀察的字型、畫面參數、WebGL、Canvas、平台等訊號組成，**不是**單一能指定的裝置 ID，也不是 `contextId`。
- **目前實作**：所有 profile 使用同一個 GeckoRuntime；Gecko 原生 `setFingerprintingProtection` 套用於**整個 Runtime 的所有分頁**，不能宣稱不同 profile 已有不同指紋。
- **隱私方向**：保持一致的瀏覽器環境、原生抗指紋保護、分離網站儲存資料，不要對同一網站每次請求都隨機改 UA、Canvas 或時區，否則可能造成更強的可識別性及登入相容問題。
- **不提供**：以偽裝不同真實裝置、規避登入安全檢查、繞過 Cloudflare／Google 的安全控制為目的之指紋輪替。

## v0.6.2 實際功能

- `GET /v1/privacy`（可加 `X-Browser-Profile: G002`）：回報 Cookies/Storage 隔離方式、Gecko 指紋防護是否啟用、明確的 `fingerprintingProtectionScope: "shared_gecko_runtime"` 及 `independentPerProfileWebFingerprint: false`。
- `POST /v1/settings/fingerprint-protection {"enabled":true}`：切換 Gecko **原生** `setFingerprintingProtection`，全 Runtime 生效，不是自訂「G002 指紋」。
- Gecko Runtime 啟用 Global Privacy Control，並保留既有追蹤保護與第三方 Cookie 隔離。
- UI 選單新增「容器隱私隔離狀態」及「全域指紋防護」開關。預設抗指紋開關為關，避免突然影響原有登入網站；使用者可自行開啟。
- Python MCP 新增 `browser_privacy`（唯讀）、`browser_fingerprint_protection`（全域設定）。

## 真正每容器獨立指紋的技術限制

Mozilla 官方 GeckoSessionSettings `contextId` 文件只保證網站儲存隔離；GeckoRuntimeSettings `setFingerprintingProtection` 文件明確寫「all tabs」。因此目前不能用現有的 API 直接實作「每個 profile 擁有獨立而可信的指紋」並承諾不互相識別。這需要額外的引擎/程序隔離架構及真機驗收，亦可能嚴重增加 RAM、耗電與啟動延遲。

官方參考：
- [GeckoSessionSettings contextId](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSessionSettings.Builder.html)
- [GeckoRuntimeSettings anti-fingerprinting](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)

**狀態**：Source 變更完成；CI 編譯與真機網站兼容性另行確認。不將 CI 通過視為網站無法識別。
