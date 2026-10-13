> **2026-10-14 最新驗收修正：** [精確閒置與產品交接](V0_14_1_IDLE_RESULT.md)。v0.14.1 固定簽章產品已發布並通過兩個 Android 系統的覆蓋更新；舊 1／10／30 分鐘資料是累計檢查點。PR #7 已通過兩個 Android 系統各 22 組與獨立 60／600／1800 秒閒置，本次出口 IP 的回歸測試亦通過，發布須保留各來源的證據。

# v0.14.1 — 下一個 AI 接手入口

使用者要求完成所有可執行工作，含編譯與 repo 內交接檔。G1–G20 以最新需求為主；後續權限修正優先：**AI／UI 共用普通瀏覽器權限**，不要將登入、prompt、檔案、下載或網站權限限制為只能人類。顯式 takeover 是擁有者選擇的暫停機制，已授權 API 可明確交還；Android OS 權限須實際成立。main 發布已授權，不再重問；維持原 key 與原容器。

## 已完成與已驗證

App／API／contentBridge／MCP／SDK 0.14.1、code 24、schema 4、82 tools。已驗產品 sourceRevision **e20ffc97559a2857acb5e5bd64b0d8b1b0f3f153**；[CI 38426201545](https://github.com/Tsenjii/Abwr-/actions/runs/38426201545) **SUCCESS**。[APK／證據與原憑證](V0_14_1_BUILD_RESULT.md) 已永久保存於 [v0.14.1 prerelease](https://github.com/Tsenjii/Abwr-/releases/tag/v0.14.1)。文件提交的 HEAD 可以比 APK source 新，先 fetch main；不可因文件 HEAD 不等於 APK SHA 而重做或把它當混裝。

Python 68／JS 六組／JVM 63、Debug／固定簽章 Release PASS。Android13/API33 與 Android15/API35 **各 22 PASS、0 FAIL、0 NOT_RUN**，另有資料保留升級 PASS；HTTP／MCP 各 100 輪。背景鎖屏實際 60.5/600.5/1800.5 秒（33）、60.6/600.5/1800.5 秒（35），各三點同 PID、新 document/JPEG、綠藍像素實測，native crash buffer=0。

G1–G20 對照及 HTTP／MCP、參數／界限／錯誤／migration：[自動化契約](V0_14_AUTOMATION.md)、[API](API.md)、[OpenAPI](openapi.json)、[MCP schema](mcp-schema.json)。包含 timed native touch/key、幾何／分頁／開放 shadow/frame、事件泵 waits、唯讀 opt-in 網路、共享 scoped prompt／file／download、敏感值遮蔽的同意除錯匯出、原子截圖 metadata、持久 takeover/audit、queue／版本診斷、stdlib SDK／15 槽位閘道與持久配額。

本次補齊：固定 built-in Gecko getter 依實際 native tab 的 unsafeSessionContextId 隔離私密代理；公共 firefox-private 不作識別。普通 context IDs 保留；私密設定只放記憶體。移除重複 Gecko focus 重設，按 surface generation 恢復 Android 焦點並排入 UI 事件序，原生逐鍵實際 abc／trusted events PASS；startup URI 排程保留擁有權，舊回呼不得蓋過新導覽。不重送不確定寫入。

15 fixture profiles 循序實測；一／二路唯讀各十輪穩定，**maximumReadConcurrencyProven=2，writeConcurrencyProven=null**。不是 15 路並行或不同設備指紋。兩個私密 profile 首導航／新 tab／同 URL 路由與上游斷線不直連 PASS；受控 HTTP／HTTPS／SOCKS、TLS／本文／Gecko response 通過。

## 尚未完成的外部驗收

**SHARP R8s Android13 真機、實際獨立 15 出口、第三方授權帳號 OAuth／Passkey／DRM：NOT_RUN。** 工作環境沒有手機/adb/出口配置；本輪本機 exec environment 後段離線，但透過 GitHub connector 與 Actions 完成驗證、發布及 repo 保存。不能以 CI 或缺少 crash 存取權限宣稱 DEVICE PASS；仍非正式 V1。

下一個具有手機存取能力的 AI：

1. 讀 [Termux 證據指南](V0_14_1_TERMUX_EVIDENCE.md)、[真機驗收](V0_14_DEVICE_ACCEPTANCE.md) 與本版 APK 雜湊／原憑證。覆蓋更新、保留登入及資料；更新 repo/bridge，重啟 MCP client，核對 diagnostics source 與全部版本。
2. `python3 scripts/termux_acceptance.py --create-test-profiles --profiles 15 --cleanup-created --concurrency-rounds 10`，唯一 TEST_AUTO profiles，保留原選擇，只清本輪新建資料。
3. `python3 scripts/termux_lifecycle.py --mode foreground --create-test-profile --cleanup-created`，再以相同 flags 分別改為 `--mode background` 與 `--mode locked` 執行；確實看到指定 state 後量測 1/10/30 分鐘。保存 PID／processInstanceId、document／timestamp／JPEG、wake lease 與可取得的 crash/exit-info。stdlib 腳本 pixelColorVerified=false，需要實看圖片，不能把 SHA 變化當像素已驗。
4. 使用自己已授權的 1–15 upstream 設定啟動 `bridge/slot_gateway.py`，再 `scripts/termux_network.py --manifest ... --create-test-profiles --cleanup-created`；核對 TLS、目標已知 SHA、Gecko response、真實且不重複的 IP。全池冷卻明確 QUEUE_TIMEOUT，達配額停止，不換槽硬闖。
5. 把各項 PASS/FAIL/NOT_RUN 和證據更新回 repo；不要回頭重做已綠的 G1–G20 通用實作。

固定 API 127.0.0.1:8765（health 之外 Bearer）、槽位 7901–7915、控制9091、fixture8877。Token 僅在環境；檔案位於 Termux 家目錄 `~/.workbuddy-ai/{ledger,shots,state}`。不輸出 Cookie／密碼／權杖原值、不解算驗證、不偽裝或輪替識別、不保證程序被停止後執行；測試自有/授權帳號並遵守站方條款與速率限制。

## 失敗與保留的證據

PR attempts 1/2 曾因 UI 已在 group／GET 用錯、startup、native abc 丟字、LRU 後空 document guard 失敗；PR #6 最終 run38415083521 四個 API/suite jobs SUCCESS（全套21 PASS，長測明示未跑），已合併。Product attempts1/2/3 分別抓到書籤 string-array 誤判、API35 startup timeout、即時 force-stop 後 storage=null。原 timeout 未再現，不能憑空宣稱 native root cause。

最終升級先用 ABWR event pump 等20秒，舊版本身重啟只讀 green，再 -r 覆蓋，之後不重植資料；兩個環境保存原憑證、Token、context IDs、tabs、bookmarks、隔離持久 Cookie、localStorage 與 takeover。這與 Gecko 非同步落盤限制一致；不保證剛寫完立刻 force-stop 不丟資料。v0.11／v0.13 原生崩潰歷史仍需真機追蹤。

[完整本輪證據](evidence/V0_14_1_CI.json)、[production attempt1](evidence/V0_14_1_PRODUCTION_ATTEMPT1.json)、[attempt2](evidence/V0_14_1_PRODUCTION_ATTEMPT2.json)、[attempt3](evidence/V0_14_1_PRODUCTION_ATTEMPT3.json)、[PR attempt1](evidence/V0_14_1_ATTEMPT1.json)、[attempt2](evidence/V0_14_1_ATTEMPT2.json)；v0.14.0 完整基準保留於 [舊版建置紀錄](V0_14_BUILD_RESULT.md)，不混用為本版驗收。
