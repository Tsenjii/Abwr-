# Abwr v0.13 SHARP 驗收 Prompt

將下列內容交給已能控制你的手機／Termux 的本機 AI。測試現有 Abwr，不卸載、不清 App 資料，不更換原有 Token，不接管你另外使用的 Chrome／Brave。

```text
請驗收 Abwr 0.13.0／versionCode 22。先讀 Repo docs/V0_13_BROWSER_FUNCTIONS.md、docs/API.md 與 skills/abwr-browser/SKILL.md，再讀 GET /v1/diagnostics 的 installedApplication。產品 applicationId 必須是 dev.local.aibrowser，固定 certificateSha256 必須含 c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a；sourceRevision 依本版紀錄核對。

Token 只從手機現有環境取得，禁止貼到對話、報告或日誌。遇到本人必須操作的系統選擇器或登入確認，記為等待本人／BLOCKED，不捏造 PASS。保留原有容器和帳號，用新的 ABWR_V013_TEST 容器及自己啟動的測試頁測試，完成後只移除本次測試資料。

按項目保存 PASS／FAIL／BLOCKED／NOT_RUN，附簡短步驟、實際結果、HTTP code 或 Android 退出原因：

1. 覆蓋安裝後原有容器、書籤、一般網站登入仍在；核對實際安裝版本／code／sourceRevision／簽章。不要用 .dev 測試 App 代替產品更新。
2. 新容器能 navigate/read/type/click，bridge 不是只有 API 存活。測 HTTP 與真 MCP 各 50 輪，操作後讀頁面驗證；MCP tools/list 有 68 tools。
3. GET／POST X-Browser-Profile 正確作用於指定容器，header/body 衝突會拒絕；關閉分頁不影響另一容器。刪除並重建同名測試容器，舊 Cookie 不恢復；不能因測試刪掉原有容器。
4. 兩個顏色不同的測試容器／分頁反覆切換，首次即時 JPEG 必須是目標顏色。保存容器、tab、documentId、URL、尺寸和時間，不能只看 metadata 或舊快取。
5. 中文深淺主題、多分頁縮圖、搜尋、多選、群組改名改色與移動，重開 App 後保存；人工接管 20 輪，寫入拒絕，讀取正常，交還後可以接續。
6. 書籤／歷史搜尋、HTML 匯入匯出、閱讀檢視、字體與手指縮放。PDF／JPEG／TXT 另存必須打開真實檔案核對內容；超長文字若被截短要明確記錄。
7. 測正常文件上傳、帶測試 Cookie 的大於 2 MB 網站附件下載、完成／取消／另存；檔案位元組與來源一致。網站權限只由本人確認，修改後重新開頁核對。
8. .user.js 用自己可控制的普通測試頁加入可見文字，儲存、重新載入、停用後再載入，必須確實生效與消失。Mozilla 商店簽章安裝、popup／options、啟停移除；不以「清單出現」代替真正工作。
9. 自己的測試 API 驗證 POST/header/body、同容器 Cookie、匿名 native、4xx／5xx、二進位及逐段串流／取消。DevTools opt-in 錄製真實 Console 與請求／回應本文；秘密欄位不得出現在公開回報。
10. 網頁正常進入全螢幕、Android 返回先退出全螢幕，工具列與頁面恢復。無痕容器 Cookie 與一般容器隔離，不進歷史／復原清單／磁碟縮圖；重啟不恢復無痕頁。主動下載和另存檔案仍保留。
11. App 切背景及關螢幕 1／10／30 分鐘，測 status、navigate/read 和新文件 JPEG。記錄主程序 PID、screenInteractive、Surface、age 與失敗原因。再做 8 小時 SHARP 省電觀察，未實際等待即 NOT_RUN。蒐集可取得的 native crash buffer／exit-info，不能只用 API 未回應推斷原因。
12. 用本人合法帳號測常用網站登入及重啟保留；OAuth／Passkey／DRM 分站記錄真實結果。本人處理網站確認或 2FA，只以確實登入頁為成功證據。

交付一份繁體中文報告，開頭列版本、sourceRevision、裝置、Android 版與測試時間；列出全部失敗和未測項目，不在有 P0 FAIL 時宣稱 V1。截圖只保存測試頁；Token、登入 Cookie 與私人網頁內容不得入報告。
```
