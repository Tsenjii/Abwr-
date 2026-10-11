# Abwr v0.9.0：AI 通道與操作可靠性

更新：2026-10-11。SOURCE 已實作，Python 30 項／JavaScript 三組本機 PASS；Kotlin／Android CI 尚待填寫。**本輪没有新增 DEVICE PASS，仍不是正式 V1。**

v0.8.0 已經使用者同意合併 [PR #1](https://github.com/Tsenjii/Abwr-/pull/1)，merge `f6139932a31c4cb6acf13d1198513445de77698b`。本輪沿用使用者對專案及 main 發布的授權，繼續實作、驗證、同步。

## 修正的實際問題

| 觸發情境 | 原行為 | v0.9 實作 |
|---|---|---|
| Native Port 斷線 | content script 不再連線，可能只能 reload | 有限退避重連；每輪最多八次，取得 native 確認後重置；不重送舊操作 |
| BFCache 暫存／恢復頁面 | 可能保留過期 Port／DOM observer | pagehide 斷開並清理；pageshow 重新建立，保留同頁表單 |
| wait 未找到 | found:false 仍回成功的 tool result | WAIT_TIMEOUT、MCP isError；斷線釋放 observer／timer |
| 切分頁／SPA 網址變更 | Agent 可能操作與先前觀察不同的頁面 | 可檢查 tab／expectedUrl；ref 的網址變了即失效 |
| HTTP 的 page work 長等待 | 三個 workers 被全部佔住，status 也排隊 | 控制與 page work 各有有限 workers／queue |
| MCP tools/call 等網站 | stdin loop 被阻塞，ping／取消無法即時處理 | reader 獨立；page tools 單序列，另有控制佇列 |
| MCP timeout／JSON array | 可能落到 internal JSON-RPC error／非預期資料 | 有明確工具失敗代碼，JSON 限 object、response 上限 8 MiB |
| loopback API redirect | urllib 可能跟隨 redirect | 不跟隨，憑證留在已驗證的 host |
| plain contenteditable／唯讀欄位 | 不支援 editor；可能假報輸入成功 | 純文字輸入／清空，拒絕唯讀、停用、隱藏與超量文字 |
| scroll y=0 | 被當成 600 pixels | 正確保持位置 |

## Agent 正常工作流程

1. 用 `browser_capabilities` 確認實際能力，`browser_status({profile})`／`browser_tabs({profile})` 確認目前頁面。
2. 開頁面後驗證目的 URL、title 和實際內容；loadSucceeded／page stop 不等於 HTTP 200，更不等於登入成功。
3. 閱讀用 `browser_read`，互動前用 `browser_snapshot` 取得 ref。
4. 以當前 `profile`、`activeTab`、完整 `url` 作為 `profile`／`tab`／`expectedUrl`，再送 click、type 或其他 page tool。前置條件不會自動選分頁。
5. PAGE_CHANGED／STALE_REF 時重新觀察。BRIDGE_NOT_READY／BRIDGE_DISCONNECTED 時等待重連並重取 snapshot；重連不保證 Android 背景 JS 一直執行。
6. 操作後驗證可見結果。寫入若 timeout／斷線，先查是否已生效；不自動重複點擊或提交。
7. 用 browser_takeover 交給本人；status／diagnostics 在 page work 卡住時仍有獨立 worker 可服務。

## 有限資源與取消

- Android HTTP：control 3 workers／12 queue，page 2 workers／8 queue。讀 request 的 idle timeout 5 秒；Page Future 仍用既有總 deadline。各佇列滿載回 503 BUSY。
- Python MCP：page 1 worker／8 queue，control 1 worker／4 queue。JSON-RPC 可按 ID 回應，不承諾回覆順序等於輸入順序；stdout 有鎖，避免不同 thread 拼壞 JSON。
- `notifications/cancelled` 可讓未開始的 MCP 工作不送 HTTP；已送出的工作只抑制遲到結果，不能回滾或保證停止頁面副作用。HTTP 自己的 deadline 仍會清理 Future。
- 關閉 MCP stdin 時只給短暫收尾，不排空多筆 23 秒工作。Legacy 協定明確支援 2024-11-05／2025-03-26；不宣稱實作 2026 stateless lifecycle。
- Plain contenteditable 直接更新文字並送 input／change；不是 OS 鍵盤、IME，也不保證所有 React／ProseMirror editor 相容。每個網站仍要驗證輸入結果。

## 自動驗證與真機範圍

- Python 新增七項實際 loopback HTTP／stdio 回歸，證實 redirect 不轉送、非 object 為失敗、參數拒絕、profile／precondition 保留、wait timeout、socket timeout，以及 blocked page request 期間的 ping／status／取消排隊點擊。
- 新增 JS lifecycle fixture，以可控制的 Port、timer、observer 驗證八次重連上限、BFCache 恢復、舊回覆不跨 Port、URL/ref 失效、editor、readonly、zero scroll 與 WAIT_TIMEOUT。
- 新增兩項 Kotlin JVM 行為測試：Page pool 滿载時 control 能回應且 queue 有限；stop 不執行 queued work 並拒絕新工作。連同既有十三項，CI 預期執行十五項。
- CI 與 APK 的精確 commit／run／artifact 待本輪建置完成填寫；不將 mock／JVM 測試列為 DEVICE PASS。

手機驗收：保留舊 App 資料、核對簽章；先在新的測試容器操作。前景與退背景／鎖屏 1、10、30 分鐘分別測 status、read、snapshot、輸入及即時截圖。再測返回已暫存的頁面、表單是否保留／bridge 是否恢復、人工接管、快速切分頁與過期 precondition 拒絕、MCP 50 次開頁→觀察→操作→驗證。登入、Cookie 隔離及下載／上傳仍按 [v0.8 裝置清單](V0_8_DEVICE_CHECKLIST.md) 逐項驗收。

## V1 仍有的阻塞

背景 fresh 畫面與 Android/OEM 長期存活、正規 Google/GitHub/Cloudflare 登入、持久 release signing key／覆蓋更新、容器長期登入、原生長按與權限中心、插件／使用者腳本。這輪強化 AI 通道；不能從 CI PASS 推論這些已完成。

協定依據：[MCP legacy cancellation](https://modelcontextprotocol.io/specification/2025-03-26/basic/utilities/cancellation)、[lifecycle](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle)、[Mozilla runtime.Port](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/runtime/Port)。
