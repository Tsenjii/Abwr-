# v0.12 工作紀錄 — 尚未驗收

2026-10-12，使用者要求全頁 UI、動畫、常用功能、DevTools、腳本管理器與擴充商店。這份文件只記錄 SOURCE；編譯與裝置結果要另行填寫。

## 已核對的 v0.11 真機回報

- POST 路由忽略 X-Browser-Profile，可能關掉 UI 容器的同名 main 分頁。現在統一套用容器 header，body/header 衝突回 BAD_ARGUMENTS。
- 新容器 bridge 偶發不能 ready：根因尚未證實。現在於內建擴充 ready 後啟動第一個載入、document_start 建立 bridge、8 次快速重試後每 30 秒重試；成功連線後無週期喚醒。
- 刪除名稱永久不能重建：Gecko 157 清理沒有完成 acknowledgement。既有 contextId 原樣遷移，新容器改用不重複的內部 contextId，名稱可重建；不宣稱舊儲存已清完。
- 切換後首張截圖偶發為其他容器像素：需要第一張 JPEG 的實際顏色重驗。不可把 metadata 一致當作像素正確證明。
- 真機 HTTP 50 輪有一次需重試，不是無重試 PASS；MCP 操作受當時 SSH 環境阻擋。真機背景 5/10/30 分鐘、8 小時、OAuth 等仍待測。

## 本輪實作

原生共用頁面（含鍵盤／系統列 insets、觸控 48dp、淺深色、減少動畫）；下載卡片與進度、書籤／歷史搜尋及逐筆選項、持續尋找控制、分組設定。擴充商店取 Mozilla Android API，Gecko 驗證簽章與權限，啟停／更新／移除／面板／設定頁。

內建 .user.js 管理使用 Firefox userScripts sandbox，不在 Abwr 控制 bridge 的權限世界執行任意 code。支援 @match／@exclude-match／@run-at／GM_addStyle／GM_log，其他 GM 或外部資源在儲存前拒絕，提示使用完整 Tampermonkey／Violentmonkey。

DevTools 原生 Console／Network／DOM／Storage 頁面。Console 與網路 opt-in、固定上限、目前分頁 scope；網址查詢與敏感標頭遮蔽，不記錄回應本文。不是 CDP，也沒有任意 eval 端點。DOM 檢查尺寸、CSS、屬性和子元素。

## 必須完成的門檻

- Android debug/release 編譯、固定憑證、Python／JS／JVM。
- Header scoped 關頁與冲突拒絕，原容器資料保留，刪除／重建容器 bridge 與隔離。
- 第一張像素、非 UI 容器與背景截圖，1/10/30 分鐘 screen-off；不能只重拍後判定 PASS。
- 實際商店簽章擴充安裝、popup/options、啟停／更新／移除；腳本 import／match／grant 拒絕／停用後 reload。
- Console／Network 實際回傳與頁面/容器隔離，原生 UI 截圖人工檢視。
- SHARP 真機未執行項目必須標 NOT_RUN，不推出正式 V1。
