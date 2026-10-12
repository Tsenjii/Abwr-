# Abwr v0.13 瀏覽器功能與驗收紀錄

版本：0.13.0 / code 22。2026-10-12。接續 v0.12 及尚未提交的 HTTP／Surface 修正，以使用者要求的日常瀏覽器完整度為主。

## 功能入口

| 類型 | 真實功能／入口 |
|---|---|
| 瀏覽 | 底部網址搜尋、前後頁、載入中停止／重新載入、首頁、桌面網站、外部 HTTP(S) 連結 |
| 分頁 | 真 GeckoSession 分頁、搜尋與真縮圖、多選、分頁群組、群組改名改色、新增及移動、關閉分頁復原 |
| 容器 | 獨立 Cookie／Storage、多帳號正常登入、建立切換刪除、同名重建使用新的內部 contextId |
| 無痕 | 選單 → 新增無痕瀏覽。使用 Gecko private mode；不寫入歷史、分頁、群組、接管狀態與磁碟縮圖。App 程序重啟不恢復無痕容器。主動下載／匯出的檔案保留 |
| 網頁操作 | 原生鍵盤、觸控、拖曳與滑動；DOM 表單、下拉選單、可見 label 勾選、shadow root、跨來源 iframe |
| 圖片連結 | 網頁長按 → 新分頁、無痕開啟、複製、分享與收藏；圖片／媒體可另開分頁 |
| 閱讀 | 原生頁面尋找、閱讀檢視、可選取文字、50–200% 字體比例、允許手指縮放 |
| 網站 | 選單 → 網站資訊與權限；真實 TLS 資訊、按容器查詢與調整網站權限、定位／通知／跨站儲存、攝影機／麥克風來源選擇 |
| 媒體 | 網頁 HTML 全螢幕事件隱藏瀏覽器列，Android 返回先退出全螢幕；由 Gecko 處理媒體解碼 |
| 書籤歷史 | 搜尋、逐筆開啟複製分享刪除；清除目前容器歷史；Firefox／Chrome Netscape HTML 書籤匯入匯出（250 筆上限，合併去重，不執行匯入 HTML） |
| 檔案 | 網站單／多檔案上傳由 Android 選擇器完成；下載進度／取消／開啟／分享／另存／移除；選單 → 另存網頁為 PDF、目前畫面 JPEG 或文字 TXT |
| 分享整合 | 系統分享、其他瀏覽器接手、主畫面網站捷徑 |
| 隱私 | Gecko ETP、Cookie 分區、GPC、全域原生指紋防護；不能稱為每容器獨立指紋 |
| 擴充 | Mozilla Android 擴充商店、Gecko 簽章與權限確認、啟停更新移除、popup 與 options；可安裝廣告攔截／完整腳本管理器 |
| 使用者腳本 | .user.js 匯入編輯啟停、@match／@exclude-match／@run-at；Firefox userScripts sandbox；有限 GM API，完整 GM 功能交由完整管理器 |
| DevTools | 原生 Console／Network／DOM／Storage；opt-in 目前文件錄製、有限且遮蔽敏感欄位的請求／回應本文、DOM 屬性／CSS、HTTP 請求工具 |
| HTTP 與 AI | 同來源 page Fetch 使用目前容器 Cookie；其他來源 native 不繼承 Cookie；POST 等方法、base64 二進位、真正增量串流／poll／SSE／取消；本機 Bearer API、MCP、人工接管 |
| 背景 | service-owned GeckoDisplay、有限 Surface 重建、工作 lease、非 UI 容器截圖；每次核對文件與 Surface，不能將快取當即時截圖 |

## 本輪修正

- POST 路由統一使用 X-Browser-Profile；header／body 衝突明確拒絕，資源回應包含 profile。
- 新容器使用獨立內部 contextId；刪除後可重建同名容器，保留舊有容器 contextId。
- document_start bridge 連線、就緒後第一個載入、delegate 重試與完整文件 load 核對。
- 切換後重新綁定全新的 GeckoView Surface，遮蓋到新文件 paint；首次 JPEG 的顏色必須正確。
- 驗收腳本版號與 MCP 工具數核對更新，不略過實際 workflow。
- 修正測試 fixture 被第二個 do_POST 覆蓋，HTTP Cookie/header/body 的驗收現在真正生效。

## 驗證狀態

SOURCE 已實作。CI、APK、簽章與模擬器結果待填；任何 NOT_RUN／BLOCKED 保留原狀。

本輪增加行為測試：書籤 HTML 交換與不可信網址排除、無痕資料不入分頁復原、原生拖曳邊界；Android 上驗證 HTTP/MCP 各 50 輪、首次截圖與容器隔離、原生 UI、真腳本執行／Network 本文／商店簽章安裝，及無痕 Cookie／不寫磁碟、分頁復原、HTML 全螢幕。

## 尚未支援或不能保證

全部桌面 Firefox／Chrome 功能並不等於 GeckoView 提供的嵌入式能力。本版不提供 Firefox Sync、Chrome 同步、瀏覽器自建密碼庫、完整 CDP、完整 GM API、任意 evaluate API、全頁長截圖、每容器代理或獨立設備指紋；也不包含網站驗證求解器。

Google／Microsoft OAuth、Passkey、DRM、OEM 省電與 8 小時背景仍要在 SHARP 真機測試。站方可能要求本人 2FA 或確認。安裝及編譯成功不能替代這些結果，未滿真機 V1 門檻不改稱 1.0。

## 更新與開啟

下載本輪 `Abwr-signed-apk` 的 ZIP，解壓 `app-release.apk`，在 Android 點 APK 覆蓋安裝；不要卸載或清資料。`Abwr-dev-apk` 是獨立 .dev 測試 App，不是產品更新檔。

開啟 Abwr 後原本登入容器應仍在。一般功能從右下選單進入；擴充商店、腳本與 DevTools 有獨立原生頁面。AI 連線資訊放在「設定 → AI 連線」，本機 Token 不提交到對話或 Git。
