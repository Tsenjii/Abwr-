# 交給本地 AI：Abwr v0.11 驗收

先從 [v0.11 建置紀錄](V0_11_TAB_GROUPS_BACKGROUND.md) 取得本次已驗證的 GitHub APK 與 SHA。安裝產品 `Abwr-signed-apk`，不要用 Abwr Dev 更新原 App。這份 Prompt 可直接交給已持有 Token 的本地 AI。

```text
請完整驗收 Abwr v0.11.0（versionCode 20），依實際結果回報建議。
先更新 repo，讀 docs/V0_11_TAB_GROUPS_BACKGROUND.md、docs/API.md 與 skills/abwr-browser/SKILL.md。更新本地 bridge/mcp_stdio.py，再重啟實際 MCP client。

保留既有 App、Token、網站登入、G001/G002、分頁與書籤；不得卸載／清資料／刪既有容器。Token、Cookie、私人網址及登入 callback 不出現在回報。
更新前留下版本/package/certificate、容器/分頁/書籤/人工接管狀態的去敏感基準。覆蓋安裝後核對 0.11.0、code 20、package dev.local.aibrowser、certificate SHA-256 c6459f9b587959be0c5c83ace5380f272113ab1a7bbcb6a8d8708ce63f78bd3a，以及原資料與 Token 仍在。Android 拒絕更新時保留安裝錯誤，不用卸載解決。

連線沿用同手機 http://127.0.0.1:8765 與既有本機 Token。電腦 AI 先使用本人已授權的 adb forward tcp:8765 tcp:8765；測試頁若在電腦則另做 adb reverse tcp:8877 tcp:8877 及 adb reverse tcp:8878 tcp:8878。MCP 以 python3 bridge/mcp_stdio.py 作 stdio server，環境 AI_BROWSER_HOST/AI_BROWSER_TOKEN 不輸出；安裝 APK 不會自動配置 client 的 MCP。HTTP 正常不代表 MCP 正常。
核對 diagnostics.installedApplication.sourceRevision 等於本輪建置紀錄的 Source commit（不要只比後續文件 HEAD）；再核對 App diagnostics 版本、/capabilities.apiVersion:0.11.0、status.contentBridgeVersion:0.11.0、bridgeSchemaVersion:3；實際 MCP initialize.serverInfo.version:0.11.0 與 tools/list 的 59 工具。如果仍是 52/54 或版本不同，先修正 client 指向的本地檔案再測，不能混用後判定通過。

所有可刪改資料都使用新的、未使用過的 ACCEPT11 測試容器。啟動 python3 scripts/acceptance_fixture.py，Abwr 載入 http://127.0.0.1:8877/（跨來源 child 是 8878）。不要對真實網站做勾選條款、購買或提交來驗測。

1. HTTP 與實際 MCP 各 50 輪：導覽／snapshot／type／click／read；記錄真實文件身分與效果、錯誤、程序重啟及耗時。另外測 fixture 的 /redirect（302 回到原 /，必須是新文件）及 /中文?name=測試（URL 與 percent encoding 等價），核對 waitForLoad、實際 URL、documentId 及 snapshot；hash 導覽則保留文件。寫入附 profile、tab、expectedUrl、documentId；過期文件/ref 必須拒絕。逾時或斷線後先檢查效果，不重送原點擊／提交。
2. 自訂勾選框：snapshot 要列 #custom-check 的原 input ref、visible:false、interactable:true、associated_visible_label；toggle(true) 後真正 checked:true，再 toggle(true) 不能反轉。#custom-disabled、#unlabelled-hidden、#hidden 必須拒絕。同時回歸 visible checkbox、select、shadow root、iframe 和 native browser_press / browser_click_point 的實際效果。
3. 相容 API：scroll direction:down/up,pixels:280 與 y:0；混用／負 pixels／錯方向要 BAD_ARGUMENTS。DELETE /v1/profiles/{id} 只刪另一個新建的空測試容器，核對原容器不受影響。
4. 人工接管 20 輪：開／關 takeover，接管期間 read/snapshot 可用，AI 點擊及群組寫入拒絕；交還後 type 正常，不靠 reload 修復。重啟後 Token、takeover 與群組保存。不能把重啟後才恢復當作本輪未出錯。
5. 群組：建至少 6 個測試分頁；在 UI 多選建立群組、改名／改色、進入群組新增分頁、加入／移出／解散；核對同容器登入與分頁保留。HTTP/MCP group 工具也各操作一次；跨容器 id 或不存在 tab 要拒絕且不改原群組。檢查雙欄縮圖、四格群組與數量、搜尋、長按、多選、深淺色和小螢幕字體。沒有 UI 操作／截圖能力的項目標 BLOCKED，不用 API 代替 UI PASS。
6. 前景 /color?c=green 真截圖；按 Home 後導覽 blue，再用 /v1/screenshot 取得新 JPEG。保存受控測試圖、文件 ID、URL、捕捉時間、完整尺寸與紅色頁首／綠藍色頁底的像素；/last 不算 live。
7. 背景 1／10／30 分鐘，在每個檢查點換新的 green/blue 文件、read/snapshot/真截圖，再回前景核對原分頁可用。同樣做鎖屏／螢幕關閉；記錄 screenInteractive、viewAttached、renderer phase、uptime/PSS/CPU。AI/Termux 若被系統暫停須區分是 client 停止還是 Abwr 失敗，不宣稱已通過。保留 OEM 省電設定。整個測試約 30 分鐘，不強制停止 App。
8. 非目前容器截圖：保留目前 UI 容器，對另一個受控測試容器 navigate/read/screenshot；不同顏色圖片必須對應各自文件，UI activeProfile 不應改變。前景／背景各試一次。
9. 原有書籤／歷史／人工接管／5 MiB attachment 下載回歸；下載確認或真正身份驗證使用本人正常流程，不繞過。

每項標 PASS/FAIL/BLOCKED/NOT_RUN，附最小去敏感 request/response、時間、文件身分、圖片及原因。CI 曾在 HTTP 50 輪與 MCP 第 50 輪時出現 Gecko SIGSEGV，根因未確認；特別觀察同一程序連續操作。遇 ANR／原生崩潰／程序重啟／bridge 斷線，保存可取得的去敏感 logcat 與最小重現步驟。如已有本人 adb 授權，即使 HTTP 已失聯仍收集 adb logcat -b crash -d 及 adb shell dumpsys activity exit-info dev.local.aibrowser；沒有存取能力則標 BLOCKED。不要把服務自動重啟或抹掉失敗後的重跑 PASS 當作根因已修好。
交付 report.md、issues.md、results.json、受控 screenshots 與優先改善建議。不要把 CI 模擬器 PASS 當成本機 SHARP PASS，也不要因為編譯成功就建議直接 V1。
```
