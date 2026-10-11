# v0.11.0：分頁群組與背景截圖

本輪依使用者的分頁圖片及完整 v0.10 實機 ZIP 進行修改。Python 37 與 JavaScript 四組本機 PASS，Android／Kotlin／模擬器 CI 尚待結果；SOURCE 實作不能代替 DEVICE 驗收。

## 分頁體驗

全螢幕雙欄圓角卡片、搜尋、實際網頁縮圖、底部新增分頁。長按或選單進入多選，可建立／加入／移出群組；群組有名稱、六色與四格縮圖，點進去可開頁、加入分頁與管理群組。解散群組保留所有分頁。群組及分頁標題存在原 browser_profiles 內，保持原 Gecko contextId、Token、書籤、歷史及人工接管資料；群組序號持久化，刪除後重啟不重用 id。縮圖只在 App 私有 cache，記憶體 8 MiB、磁碟 96 張／12 MiB，只載入可見卡片。

群組也有 HTTP/MCP 操作；完整契約見 API.md。分頁 metadata 限制提高到每容器 120 額外分頁，但 Gecko resident idle limit 不因群組增加。

## 背景截圖與 AI 操作

- 原 service-owned GeckoDisplay/ImageReader renderer 保持，不截其他 App 或手機桌面。
- 截圖請求等待原 session 的 bridge，最多 12 秒；背景 compositor 異常時重建一次 Surface，保留原文件、不 reload。遲到結果、導航／分頁改變及尺寸錯誤不得寫入 cache。
- 非 UI 容器截圖使用暫時私有 display，完成或取消後釋放；不切換使用者目前容器。
- API 工作持有有時限的 CPU wake lease，成功／錯誤／取消 finally 釋放，避免 FGS 在鎖屏時只保留程序但請求 CPU 休眠。未常駐喚醒手機。
- Bridge 2.5 秒沒 native ack 也納入有限重連；DOM 命令送出前短等重連，已送出的 click/type/submit 不自動重送。
- 自訂 checkbox/radio 只使用真正 associated visible label，snapshot 回原 input 的 ref 和 checked 狀態。disabled／無可見標籤隱藏 input 仍拒絕。
- 舊 scroll direction/pixels 和 REST DELETE profile 相容；App/API/content script/MCP 的版本及實際工具清單可查，避免混用舊 bridge。

## v0.10 真機報告如何解讀

SHARP Android 14 報告的最終截圖案例：viewAttached:false，1080×1910 的 green 與 blue 新文件 JPEG，短時間背景截圖 PASS。早期 screenshot BLOCKED 記錄與最終案例不一致，以最終圖像案例為短時間證據；1/10/30 分鐘和鎖屏未跑。實際自訂勾選框 FAIL、bridge 反覆 NOT_READY、舊 scroll 參數及 DELETE 路由問題在本輪修正。iframe／native key 已在 v0.10 源碼有實作，但回報使用的 MCP 工具數為 52，最新源碼為 54；本版明示版本並增加到 59。

不把報告內私人頁面、登入 callback、Token、原始截圖放進公開 repo。不宣稱已證實 bridge 故障唯一根因，也不宣稱新版本 SHARP 長時間背景已通過。

## 驗證與發佈

第一輪 [CI 38163899255](https://github.com/Tsenjii/Abwr-/actions/runs/38163899255)：Android Debug/Release、Python 37、JavaScript 四組及 Kotlin 28 通過，模擬器抓到 MCP 測試函式 name 參數撞名、群組新增分頁後導覽逾時、非目前容器 bridge 未就緒。受控背景藍色 JPEG、HTTP 50 輪及人工接管 20 輪通過；長時間測試因快速驗收失敗而未開始。已修測試參數名、以新文件 complete 狀態判定導覽、對背景 API 工作短暫啟用原 session；下一輪待實測，不抹除第一輪失敗。

待 GitHub CI 建置 Android Debug／同永久 key Release，跑 Kotlin、HTTP 50 輪、MCP 50 輪、20 輪人工接管、真實 UI 建群組／多選／改名／改色／新增分頁／重啟保存、前景／背景／非目前容器 JPEG，最後鎖屏背景 1/10/30 分鐘新文件像素及回前景。快速測試失敗不浪費 30 分鐘長測。最終證據、APK 與 SHA 將記錄於本檔。

簽章沿用永久 ABWR_SIGNING_KEY_B64，產品 package dev.local.aibrowser，預期 SHA-256：c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a。不得用 debug/cache 新 key 發佈產品；不得要求卸載／清資料來更新。
