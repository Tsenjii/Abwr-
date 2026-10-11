# Abwr v0.8.0 建置與交接結果（2026-10-11）

**SOURCE / CI PASS；本輪沒有新增 DEVICE PASS，尚非正式 V1。**

- 功能稿：[草稿 PR #1](https://github.com/Tsenjii/Abwr-/pull/1)，分支 `codex/abwr-v0.8-browser-reliability`。
- 成功 CI：[Build Android APK #38124979747](https://github.com/Tsenjii/Abwr-/actions/runs/38124979747)。
- 完整驗證的程式碼 commit：`4b7c3a43cb2ff34f2fb2aeb29dc49cc0eb65c398`；後續文件提交不改動 Android／Python／JavaScript 程式碼。
- Python **23** 項、Kotlin JVM **13** 項、兩組 WebExtension JavaScript、Android Debug APK 編譯、APK 憑證核查、Artifact 上傳全部成功。
- Artifact：`Abwr-debug-apk`，ID `11696678667`，未過期。ZIP **263,887,505 bytes**，內有 APK 和公開憑證指紋 `signature.txt`。
- ZIP digest：`sha256:38ff24e40f4d797c37af1b9c12f90bdc8e911e2f74d7aec5e974c314e10952dc`（不是 APK 簽章指紋）。

## 最終變更

1. 原 Gecko 回應串流的下載確認、進度、取消、開啟、分享及 Android 文件選擇器另存；按容器記錄，也能查看全部容器，刪除容器後仍可管理其已下載檔案。下載不取出 Cookie、不另用 HTTP client 重抓；兩個 transfer workers、四個未完成下載上限，copy buffer 64 KiB。
2. 分頁關閉檢查在 metadata 移除前；下載、DOM 指令與導航等待中的 Session 受到保護。Native Messaging 以 Session + Port 匹配回覆，斷線/替換立即結束所屬等待，舊回覆不能冒領其他頁面的指令。
3. Popup navigate wait/URL/title/loading 獨立，不寫入父頁 metadata；返回鍵先走 Popup 歷史。HTTP Auth 提示與網站 Prompt 綁來源頁並有取消生命周期；多 MIME 檔案選擇和過期回傳保護。
4. 截圖綁原 Session/Page/Surface，最多等待十二秒；編碼在背景 worker、Bitmap 回收、跨 Surface 不寫快取，維持原前景 viewport；仍不能以此宣稱背景 fresh 截圖真機成功。
5. 日常選單、深色／淺色／系統外觀；HTTP bounded queue / 503 BUSY、Service 停止關 sockets、逾時取消尚未開始的 UI 工作與內層等待。
6. 驗收腳本不會再將 API timeout/errorType 隱藏成空正常報告；API 活著但網頁 bridge 尚未 ready 時退出失敗，報告也不輸出私密網址/標題。新增三項 Python 回歸測試。

## main 與安裝

本輪 main 仍為 `31d4276601cdd80a54d8948a88a4f4b6940e7c0a` / v0.7.1；程式與文件已同步到可審查的 PR 分支。自動審查拒絕直接推送 main，要求使用者明確批准這個具體變更的發布；未合併、不啟用自動合併。接手先查 PR/main 最新狀態；若使用者後來已同意，沿用授權，不要重複問。

不要求卸載／清除 App 資料。舊 debug APK 簽章可能和新 CI 不同；快取新 debug keystore 只是最佳努力，無法復原舊私鑰，也可能被清除。正式 V1 仍需持久 release signing key 和覆蓋安裝保留登入狀態的真機驗收。

真機下一步：[V0_8_DEVICE_CHECKLIST.md](V0_8_DEVICE_CHECKLIST.md)。背景 1/10/30 分鐘、鎖屏、Google/GitHub/Cloudflare、容器 Cookie 長期保持、下載/上傳仍要在手機逐項測。程序被停止的下載不自動續傳；沒有本機設備證據就標未測。
