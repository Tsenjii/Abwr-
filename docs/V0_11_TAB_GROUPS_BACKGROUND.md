# v0.11.0：分頁群組與背景截圖

本輪依使用者的分頁圖片及完整 v0.10 實機 ZIP 進行修改。群組／背景完整 CI 已通過，最後追加重新導向與中文網址回歸；各輪來源與範圍列於下方。SHARP v0.11 真機仍待驗收，版本維持 0.11.0，非正式 V1。

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

| 驗證輪次 | Source commit | Actions | 範圍／結果 |
|---|---|---|---|
| 群組／背景完整基準 | `e3d616c3877dc160756c38194f336e608ec5488a` | [38166025803](https://github.com/Tsenjii/Abwr-/actions/runs/38166025803) | Python 37、JS 四組、JVM 33、Android Debug/Release、固定憑證及 12 組模擬器驗收 PASS，含 30 分鐘關螢幕 |
| 導覽修正版首次回歸 | `c36e47f63c95aaa24b82ee2680003fcf3e6bb0e2` | [38169364163](https://github.com/Tsenjii/Abwr-/actions/runs/38169364163) | 建置／37 Python／四組 JS／35 JVM／簽章 PASS；操作回歸 FAIL，Gecko 原生崩潰，長測未開始 |
| 原生崩潰證據收集與重現 | `d79adc67e4a5e98900f5fc38339ecc0423025674` | [38170282193](https://github.com/Tsenjii/Abwr-/actions/runs/38170282193) | 建置／簽章 PASS；10 組操作 PASS，HTTP/MCP 各 50 輪與 UI／背景通過，崩潰未再現；新分頁文件 ID 不一致 FAIL，長測未開始 |
| 初次分頁載入修正 | `4c52da9150b2e4c01493b7e303a19c8cdc9804e9` | [38171622490](https://github.com/Tsenjii/Abwr-/actions/runs/38171622490) | Python 37／JS 四組／JVM 37／Android Debug/Release／固定簽章 PASS；11 組快速操作回歸 PASS；背景 renderer/wake/idle 未改，保留完整基準長測來源 |

最新 [操作證據 ZIP](https://github.com/Tsenjii/Abwr-/actions/runs/38171622490/artifacts/11713684267) 與 [JVM 證據](https://github.com/Tsenjii/Abwr-/actions/runs/38171622490/artifacts/11714445438) 已核對。`results.json` 為 11 PASS／0 FAIL／1 NOT_RUN，加一筆 cycle count；11 組含 302／中文 URL、六次新分頁立即導覽與稍後文件核對、HTTP/MCP 各 50 輪、接管 20 輪、UI 群組／重啟保存、前景／背景 12 秒／非目前容器 JPEG。HTTP/MCP 主 PID 同為 3386；UI 保存案例另外有明確的測試 force-stop/restart，不能說整個工作流從未重啟。編譯來源與實際 APK sourceRevision 一致。

最新 native crash buffer 為空，Android exit-info 沒有 native crash；後續診斷與分頁修正版兩輪均未再現第一次 SIGSEGV。**根因仍未確認**，需要真機持續觀察，不能把目前 PASS 宣稱為已修復原生崩潰。最新修正版的 30 分鐘案例明示 NOT_RUN，長測使用下方完整基準的來源與證據，並非最新 APK 重新跑過 30 分鐘。

後續源碼補強：追蹤 Gecko 原生 HTTP redirect 鏈，避免登入 endpoint 回到原網址時被誤判逾時；中文 URL path/query/fragment 與瀏覽器 ASCII 編碼等價比對。增加 JVM 負例及實際 302→原頁／中文 URL 導覽驗收。此修正版未修改 capture/display/wake/idle 行為，重新跑導覽、HTTP/MCP、UI 群組及短時間背景回歸，保留完整基準的 source/evidence。

首次導覽回歸的 302、中文 URL、HTTP 50 輪通過，但實際 MCP 完成 49 輪後，第 50 輪 `browser_navigate` 斷線。logcat 記錄主 PID 3372 的 Gecko 執行緒 `SIGSEGV/SEGV_MAPERR, fault addr 0x0`，隨後 App 死亡；後續 UI／接管／截圖也因此失敗。[失敗證據](https://github.com/Tsenjii/Abwr-/actions/runs/38169364163/artifacts/11713381668)。原本 log filter 沒收 DEBUG 堆疊，而且快速 gate 提前退出，略過最終 Android exit-info；已補 crash buffer／DEBUG／finally 收集及測試前編譯版本紀錄。尚未證明原生崩潰根因，單次重跑 PASS 也不能宣稱根因已修好。

補診斷後的 [回歸證據](https://github.com/Tsenjii/Abwr-/actions/runs/38170282193/artifacts/11713314493) 沒有 native crash，HTTP/MCP 各 50 輪、接管 20 輪、UI 保存及短時間／非目前容器截圖通過；但新分頁導覽回傳的 documentId 和隨後 status 不一致。新 session 初次載入還沒提交文件時，原程式又 stop/load 同一 URL，可能先收初次文件再被第二次載入替換。已記錄初次 pending URL，對尚無 documentId 的同目標載入合併等待，正常已提交文件仍重新導覽；新增兩項 JVM 與六次實際新分頁立即導覽／稍後 snapshot 的文件一致性回歸。這是分頁錯誤修正，不宣稱已解釋前一輪 SIGSEGV。

### 完整基準的實際操作證據

[模擬器證據 ZIP](https://github.com/Tsenjii/Abwr-/actions/runs/38166025803/artifacts/11712729996) 包含 `results.json`、`background-locked-checkpoints.json`、實際 JPEG、UI PNG、版本與去敏感測試 log。Android API 35 模擬器使用獨立 `dev.local.aibrowser.dev`；同 source 的產品 Release APK 另經固定憑證核對，不能把模擬器結果叫作 SHARP DEVICE PASS。

12 組 PASS：文件身分／快照／shadow root、跨來源 iframe、HTTP 參數與隱藏／停用負例、自訂勾選框／舊 scroll、select／native keyboard／座標、HTTP 50 輪、實際 MCP 59 工具／群組／50 輪、人工接管 20 輪／DELETE、分頁與容器路由、UI 群組與重啟保存、前景／Home 12 秒／非目前容器新像素、螢幕關閉 1/10/30 分鐘及回前景。`results.json` 另有一筆 HTTP cycle count，並非第 13 組測試。

UI 用實際觸控多選建立群組、改名／改色、進入群組新增分頁，再重啟核對群組、分頁標題、Token 與人工接管。已人工檢視深淺色群組拼圖、群組內頁及恢復後畫面；不是 UI mockup。

| 螢幕關閉檢查點 | 實際背景秒數 | 新文件／JPEG | 主程序 PID | 主程序 PSS／累積 CPU |
|---|---:|---|---|---|
| 1 分鐘 | 60.3 | blue，新 documentId，1080×1499 | 6206 | 230.12 MiB／8961 ms |
| 10 分鐘 | 600.3 | green，新 documentId，1080×1499 | 6206 | 230.87 MiB／15460 ms |
| 30 分鐘 | 1800.3 | blue，新 documentId，1080×1499 | 6206 | 234.08 MiB／29444 ms |

三點均 `screenInteractive:false`、`viewAttached:false`、背景 renderer active，實際 `/v1/screenshot` JPEG 已核對紅色頁首及對應綠／藍頁底；最後回前景取得藍色 JPEG。診斷 PSS/CPU 僅含主 App 程序，排除 Gecko 子程序，不能據此宣稱完整記憶體／耗電表現。`/screenshot/meta` 是快取描述，`stale:true` 不當作 live 證據，也不否定剛核對的新 JPEG。

CI push 跑快速回歸；Actions 的 Run workflow 預設 long_background=true，可重新跑完整 30 分鐘。背景 capture/display/wake/idle 行為修改必須跑完整長測。真機仍用驗收 Prompt 實測。

第一輪 [CI 38163899255](https://github.com/Tsenjii/Abwr-/actions/runs/38163899255)：Android Debug/Release、Python 37、JavaScript 四組及 Kotlin 28 通過，模擬器抓到 MCP 測試函式 name 參數撞名、群組新增分頁後導覽逾時、非目前容器 bridge 未就緒。受控背景藍色 JPEG、HTTP 50 輪及人工接管 20 輪通過；長時間測試因快速驗收失敗而未開始。已修測試參數名、以新文件 complete 狀態判定導覽、對背景 API 工作短暫啟用原 session；完整基準重驗通過，保留第一輪失敗。

### 產品 APK

[下載 Abwr-signed-apk](https://github.com/Tsenjii/Abwr-/actions/runs/38171622490/artifacts/11714164326)，ZIP 解壓後安裝 `app-release.apk`。來源為表中初次分頁載入修正版，0.11.0／versionCode 20／`dev.local.aibrowser`；不要下載 `.dev` 作為產品更新。

- APK：246015985 bytes；SHA-256 `71e5244ad0b91a7b2dfb3f8fd9d04855dfd1d526e60298927bd57ff1c0dca117`。
- 固定憑證 SHA-256 `c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a`，永久 Secret 來源／Gradle pin／實際 apksigner PASS。
- 已下載 APK 核對 SHA、內嵌編譯 sourceRevision 及 WebExtension 0.11.0。後續文件提交不改變此 APK，diagnostics 必須與表中 Source 比較。

SHARP 仍需依 [本地 AI 驗收 Prompt](V0_11_ACCEPTANCE_PROMPT.md) 覆蓋更新、驗證資料保存、實際 UI 與 OEM 背景／鎖屏。不要卸載或清資料。

簽章沿用永久 ABWR_SIGNING_KEY_B64，產品 package dev.local.aibrowser，預期 SHA-256：c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a。不得用 debug/cache 新 key 發佈產品；不得要求卸載／清資料來更新。
