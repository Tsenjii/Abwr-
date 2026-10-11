# Abwr V1 Release Gate（穩定與日常可用性驗收）

更新：2026-10-11。此文件是**正式 V1 的條件**；v0.10 已通過受控模擬器驗收，尚未達成真機 Release Gate。現有證據見 [建置結果](V0_10_BUILD_RESULT.md)。

## Release 原則

- SOURCE：功能已在程式碼實作，不代表能在手機運作。
- CI：Python 協定、MCP stdio、WebExtension 模擬測試及 Android 編譯均通過，不代表 UI/Google/CF 已成功。
- DEVICE：必須由**真實 Android 裝置及已授權的 Agent**重複操作驗證；保留 PASS/FAIL 與重現步驟。
- 不把 Google／Cloudflare 是否放行當成保證或可繞過的條件；測試的是合法相容性、提示與工作接續。

## P0 — V1 發布前全部需通過

| 項目 | 最低驗收標準 | 截至 v0.10（SHARP DEVICE 仍待驗） |
|---|---|---|
| API 存活及認證 | 前後台狀態返回一致，錯誤 Token 拒絕、不洩漏密鑰 | CI 部分、DEVICE 待測 |
| 分頁與容器 | 開 12 分頁、切 10 容器、前後頁 100 次，網址、Session 與快照不混淆 | 模擬器分頁計數/關主頁/profile routes PASS；完整規模與 DEVICE 待測 |
| Agent 工作流 | 任意 MCP 客戶端完成 50 次開頁→讀取→操作→驗證；過期 ref 清楚報錯 | HTTP 50 輪與實際 MCP 工作流 CI PASS；MCP 50 輪/DEVICE 待測 |
| 進行中操作保護 | Navigating/command 時 LRU 不關掉目標 Session；關 Tab/Popup 時迅速取消所屬操作 | SOURCE 修正、DEVICE 待測 |
| 登入狀態 | 以合法帳號分別測 GitHub／一般登入站；重開 App 不遺失該容器網站 Cookie | DEVICE 待測 |
| 不誤刪資料 | 刪除測試容器前，既有容器 Bookmark、Cookie 不變；清除舊儲存前 ID 不得重複使用 | SOURCE 修正、DEVICE 待測 |
| 截圖一致性 | 切頁後不回傳其他頁的舊畫面作為目前頁；前台即時 JPEG 內容與 URL 一致 | 模擬器完整 viewport 與新文件像素 PASS；DEVICE 待測 |
| Background | 鎖屏/切背景 1、10、30 分鐘，Agent 可知實際存活與 Surface 狀態，不把舊截圖當 live | 模擬器背景 12 秒後導航/新截圖 PASS；DEVICE/鎖屏/長時間未驗證 |
| OAuth 與人類接管 | OAuth Popup 的 URL/loading/closing 跟上實際頁；本人接管/交還不遺失任務 | SOURCE 加強、DEVICE 待測 |
| 安裝更新與簽章 | 同簽章覆蓋安裝不清除登入／容器；有持久 release signing key，不僅依賴可被清除的 CI debug cache | v0.9 原 key/私密備份/永久 Secret/憑證 pin 完成，已移除 cache fallback；DEVICE 覆蓋更新待驗 |
| 啟動／崩潰 | 20 次 App 開啟/重啟無資料異常，觀察記憶體及系統回收 | DEVICE 待測 |

## P1 — V1 日常使用品質

- 中文介面、深色模式、導航列進度、首頁、歷史書籤搜尋、下載管理、檔案上傳、網站權限／Popup
- ETP/抗指紋保護須可關閉且相容常見登入頁；每容器 Cookie/Storage 隔離不等於各自有不同設備指紋
- 一份不綁定 OpenCode 的 Skill、可查詢能力表及穩定 HTTP/MCP 工具命名

## v0.7 這輪工程修正

1. WebExtension Native Messaging request 實際關聯到 GeckoSession。Session 關閉即 fail，而不是任由逾時。
2. LRU 保護尚有有效導覽 waiter 的主 Session 與額外分頁。
3. API 不能在容器有正在執行的 Agent 操作時刪除容器；人工接管中的容器也拒絕刪除。
4. 容器刪除採持久化「退休中 ID」隔離，由於現用 GeckoView API 不提供清理完成回呼，刪除後持久保留舊 ID、不再重用。程序重啟會再試清理。
5. 分頁／網頁導航使舊的 JPEG cache 失效；若截圖尚未完成便切頁，回報失敗而非錯誤畫面。
6. Popup URL／標題／載入狀態和 main Tab 分別追蹤；status/tabs 真正反映目前彈窗。
7. MCP 透過完整 stdio JSON-RPC 測試，含 initialize、tools/list、tool call failure、ping、未知方法、通知無回覆。
8. **沒有**替 V1 暗中宣稱 Google OAuth、Cloudflare challenge、背景即時截圖已通過。

## V1 發布紀律

完成前版本維持 `0.x`。每次 build 都記錄對應 commit、CI workflow、Artifact 與 DEVICE 測試結果；不可只因編譯成功便稱「穩定版 V1」。
