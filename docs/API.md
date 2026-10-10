# 本機 API

HTTP 監聽：`127.0.0.1:8765`；所有 `/v1/*` 要求必須帶 `Authorization: Bearer <API Token>`，由 App 提供。健康檢查 `GET /health` 不需要 Token。

主要端點：
- `GET /v1/status`、`GET /v1/profiles`
- `POST /v1/profile/create` body `{"id":"G002"}`
- `POST /v1/profile/switch` body `{"id":"G002"}`
- `POST /v1/profile/delete` body `{"id":"G002"}`
- `POST /v1/navigate` body `{"url":"https://example.org","profile":"G002"}`
- `POST /v1/command` body `{"action":"dom","profile":"G002"}`; 其他 actions 為 `find,click,type,scroll,status,cookie_metadata,wait,select,press,focus,links`。
- `POST /v1/takeover` body `{"profile":"G002","manual":true}`；交還 AI 設 `manual:false`。
- `GET /v1/screenshot`：JPEG；只能截正在渲染的「目前可見 profile」，可帶 `X-Browser-Profile: G002`。
- `GET /v1/screenshot/full`：**尚未支援**，不可當作完整長網頁截圖。
- `GET /v1/proxy`：只回報當前 `direct` 與 `supported:false`。
- `POST /v1/proxy`：明確回 501，無原生 Proxy 實作。

範例：
```bash
curl http://127.0.0.1:8765/health
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" http://127.0.0.1:8765/v1/status
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org","profile":"G002"}' http://127.0.0.1:8765/v1/navigate
```

**權限範圍**：持有 Token 的本機 Agent 可操作瀏覽器；不可把 API 與 Token 暴露至公開網路。Web 表單、帳號驗證與外部網站安全要求仍須依正常流程完成。
