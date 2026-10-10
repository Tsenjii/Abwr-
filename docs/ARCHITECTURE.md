# 架構

```
本機 AI Agent
   │ MCP stdio bridge / HTTP + bearer token
   ▼
LocalApiServer (127.0.0.1:8765)
   │ calls main-thread BrowserController
   ▼
GeckoRuntime → GeckoSession(contextId = profile)
   │ Native Messaging bridge
   ▼
內建 WebExtension content script → DOM 操作
   │
   ▼
GeckoView render surface ← MainActivity Human Takeover
```

資料隔離：每個 Profile 對應 GeckoSession contextId；休眠 Session 可以被逐出 RAM，但原始碼必須驗證持久儲存。Agent 使用 profile ID，不依賴 Android App 當前選取的 profile。

限制：GeckoView 嵌入式瀏覽器與完整 Chrome／Firefox 是不同的受信任登入環境；Google OAuth 可能拒絕。背景 screenshot 需渲染 surface，不能保證 Activity 隱藏時成功。
