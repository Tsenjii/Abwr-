# 手機單機驗收與可追溯證據

先核對 App／API／contentBridge／MCP／SDK 0.14.1、code 24、schema 4、82 tools，以及最新 [編譯紀錄](V0_14_1_COMPLETION.md) 的 APK 雜湊／憑證／sourceRevision。使用固定簽章覆蓋更新，保留原容器；不要卸載、清資料或生成新 key。

```sh
pkg install python git
python3 -m pip install requests
cd "$HOME/Abwr-"
export AI_BROWSER_HOST=http://127.0.0.1:8765
read -rs AI_BROWSER_TOKEN
export AI_BROWSER_TOKEN
python3 scripts/v1_smoke.py
python3 scripts/termux_acceptance.py --create-test-profiles --profiles 15 --cleanup-created --concurrency-rounds 10
```

Token 由自己的 App AI 連線設定取得，上面 `read` 從本機終端安全輸入，不放命令歷史或日誌。SDK／驗收腳本只用 stdlib；`requests` 供既有上層工具使用；ABWR 路徑不 import Playwright，不讀取 Windows Chrome executable_path。MCP 以 `python3 bridge/mcp_stdio.py` 啟動 stdio；更新 APK 後同步 repo 並重啟 MCP client。

## 前景、背景、鎖屏 1／10／30 分鐘

三種狀態分開執行，fixture 與閘道的埠不得衝突。`termux_lifecycle.py` 自行開 8877，建立唯一 TEST_LIFE 測試 profile；只刪本輪測試資料並恢復選擇。開始前設定 App 電池最佳化豁免與前景服務；Termux 程序也需能持續運行。手機的實際行為由證據判定，不能假設永久存活。

```sh
python3 scripts/termux_lifecycle.py --mode foreground --create-test-profile --cleanup-created
python3 scripts/termux_lifecycle.py --mode background --create-test-profile --cleanup-created
python3 scripts/termux_lifecycle.py --mode locked --create-test-profile --cleanup-created
```

前景輪在 120 秒狀態等待期間將 ABWR 開到前景，Python 留在 Termux 執行。背景輪讓 Termux 在前景、ABWR 在背景且螢幕亮著。鎖屏輪在開始後關閉螢幕；程式必須真正看到 `viewAttached:false`／`screenInteractive:false` 才開始計時。腳本不冒充擁有 OS 裝置管理權限，也不要求使用者為一般 prompt 代按授權。能控制該設備的 agent 可自行切換狀態。

閒置期間不發 API、不持有 wake lease；monotonic 閒置計時用本機 sleep，這是在量測生命週期，沒有等待網路回應。等待頁面／事件或狀態準備時使用 ABWR 事件泵。每個實際 60／600／1800 秒 checkpoint 取得不同文件、前進的時間戳、新 JPEG 雜湊、可見綠／藍文字、同一 PID 與 processInstanceId、bridge Ready、狀態與 wake lease。任一不符即記失敗，寫入不重送。stdlib 腳本不解碼 JPEG 像素，明確 `pixelColorVerified:false`；下一位可看對應圖片驗色，不能把雜湊當像素已驗證。原生 crash buffer／exit-info 能取得時另附，不能用沒有存取權限推論沒有 crash。

## 15 個實際出口

先把自己的 upstream 設定存放在 Termux 家目錄，權限 600，帳密分欄位。不要提交、貼進回報或記錄授權值。依需求放 1–15 槽位，不能用重複直連假充獨立出口。

```sh
read -rs ABWR_GATEWAY_TOKEN
export ABWR_GATEWAY_TOKEN
chmod 600 "$HOME/.workbuddy-ai/state/gateway-config.json"
python3 bridge/slot_gateway.py --config "$HOME/.workbuddy-ai/state/gateway-config.json"
```

設定格式為 `{"slots":[{"port":7901,"server":"scheme://host:port","username":"separate","password":"separate"}]}`。槽位僅聽 127.0.0.1:7901–7915；控制面僅聽 127.0.0.1:9091。埠衝突明確 PORT_CONFLICT。Ctrl+C 會關閉閘道、保留冷卻／失敗／出口映射。郵件輪詢預設直連，上層只有明確設定才走代理。

另準備不含帳密的 probe manifest：`{"exits":[{"port":7901,"target":"https://owner-domain/known-ip-fixture","expectedSha256":"64 lowercase hex characters","expectedExitIp":"owner-known IP"}]}`。`expectedExitIp` 可省略，但此槽就不會計入獨立出口證明。若需要 IP 映射，目標應回已知 IP 的 JSON `{"ip":"..."}` 且本文 SHA 必須預先由擁有者確認；不要把未知回應自動納入期望值。

```sh
python3 scripts/termux_network.py --manifest "$HOME/.workbuddy-ai/state/exit-probes.json" --create-test-profiles --cleanup-created
```

程式逐槽實測 TCP／TLS／已知本文 SHA、Gecko 網路 response 與精確 profile；由本輪 TEST_NET profile 指向本機槽位，不改既有登入容器。`independentExitsProven` 只計實際核對且不同的 IP。全池冷卻等十秒後回 QUEUE_TIMEOUT；不換槽、不重放、不突破配額。localhost 目標由 Gecko 直連，故 manifest 明確拒絕將 loopback fixture 當出口證據。

## 檔案與交接

所有資料在 `$HOME/.workbuddy-ai/{ledger,shots,state}`，權限 600，不使用 `/sdcard`。截圖含 profile／tab／documentId／時間戳及 metadata sidecar；URL query／fragment 清除，Token 不進檔名。ledger 保留完成／中斷／清理錯誤；中斷後先讀結果與實際效果，不重送未知寫入。把來源版本、裝置自報型號／Android、每項 PASS／FAIL／NOT_RUN 與證據更新回 repo。Google/OAuth/Passkey/DRM 仍需各自授權帳號或硬體案例；fixture 不能證明第三方登入。
