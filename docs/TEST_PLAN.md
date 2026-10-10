# 驗收與測試計畫

## CI
- Python: `python3 -m unittest discover -s tests -v`
- Node: `node tests/browser_content_mock_test.js`
- Android: `gradle :app:assembleDebug --stacktrace`
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Android 真機（未執行）
- 安裝／啟動／重新開啟，檢查服務及 token
- 手機 Chrome／Brave 登入狀態不受影響
- G001、G002 網站資料隔離
- 指定 G002 執行 DOM 工具時，畫面仍顯示 G001
- 可見畫面截圖、手動接管與交還
- popup 關閉及 OAuth 回跳生命週期
- Android 背景回收與失敗恢復
- proxy 配置測試（功能實作後才執行）
- 10／30／100 profile metadata & RAM 壓力測試

## 判準
GitHub Actions PASS 只證明構建成功，不代表 Android 真機使用或 Google OAuth 成功。
