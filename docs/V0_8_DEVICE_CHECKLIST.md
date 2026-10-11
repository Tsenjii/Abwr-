# v0.8.0 裝置驗收：下載、登入提示及前後台

這輪程式碼需要 Python、JavaScript、Kotlin JVM 測試和 Android Debug 編譯。編譯通過不能代替下列真機測試；正式 V1 仍以 `V1_RELEASE_GATE.md` 為準。

## 安裝與保留資料

- 不卸載、不清除 App 資料；先比較已裝版本與新 APK 的簽章。Actions Artifact 附 `signature.txt`（公開憑證指紋，不含私鑰）。
- 舊 CI 每個 runner 可能產生不同 debug keystore，本輪加入最佳努力的 CI 快取，只改善往後連續建置，不能追溯復原舊私鑰；快取也可能被 GitHub 清除。簽章不符時先保留舊 App，正式 V1 仍需要持久的 release signing key。
- 不修改既有 Gecko contextId；下載另存是由本人在 Android 文件選擇器選位置。

## 必須在 Android 上測

| 情境 | 驗收 |
|---|---|
| 下載一般附件 | Gecko `onExternalResponse` → 確認 → 進度 → 完成；內容與原檔一致 |
| 需要登入的下載 | 從已登入的測試帳號下載成功；不取出 Cookie、不交給另一個 HTTP client 重抓 |
| 取消／中斷 | 大檔取消後不能顯示完成；截斷回應、沒空間或程序終止標示失敗，不把 `.part` 當成完成檔 |
| 背景下載 | 確認並開始後 Home → 回前台查看進度；切其他容器不把下載的 Session 當閒置回收 |
| 開啟／分享／另存 | 只授予選取檔案的讀取 URI；另存失敗保留 Abwr 原檔 |
| 檔案上傳 | image/PDF 等多種 MIME 可選，多檔可選；取消、原頁跳轉或關分頁後回來不能重複回覆舊 Prompt |
| HTTP 驗證 | 網站 username/password、password-only 可由本人填入，取消不留未完成 Prompt；與 Google OAuth 是不同測試 |
| OAuth Popup | `navigate(waitForLoad=true)` 能等 Popup 的 page stop；網址／標題不改寫父分頁；返回鍵先回 Popup 歷史，無歷史才關閉 |
| 通道斷線 | 導覽/Tab 關閉/文件換 Port 時，舊指令迅速失敗；舊 Port 回覆不可完成另一個 Session 的指令 |
| 分頁關閉被拒絕 | 正在 Agent 操作／下載時 Agent 關閉 Tab 被拒；分頁仍在列表，URL/Session 不丟失 |
| 截圖競態 | 截圖途中切分頁、關 Popup、Home／回前台或導航，失敗要明確；新圖不能來自先前 Session/Surface |
| 外觀／返回鍵 | 淺色/深色/系統模式正常；主畫面選單優先日常操作，工具藏在「工具與連線」；新分頁無歷史時返回關分頁 |

背景還需測 1/10/30 分鐘、鎖螢幕、回前台；Google/GitHub/Cloudflare 相容性及 Cookie 長期保持要獨立記錄，不能拿下載或 HTTP Basic Auth 測試代替。

診斷：本人本機設定 `AI_BROWSER_TOKEN` 後執行 `python3 scripts/v1_smoke.py --screenshot`。`lastBlockedSiteInteraction` 是先前被拒絕的隱藏頁面請求紀錄，不表示有待自動批准的 Prompt；回到來源頁由本人重試。

下載限制：兩個串流 worker、最多四個進行中下載、200 筆紀錄、64 KiB copy buffer；程序被系統終止後不自動續傳，需回原網站再下載。HTTP API 只提供經 Bearer 認證的下載 metadata，不提供任意檔案讀寫或 Cookie 匯出。
