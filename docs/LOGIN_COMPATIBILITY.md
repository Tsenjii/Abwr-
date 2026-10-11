# Abwr 登入／Cloudflare 相容性 — 狀態及驗收規格

更新：2026-10-11。**不保證任何網站的伺服器、風控或 Cloudflare 驗證必定放行。**

## 事實與限制

- **GitHub**：可針對一般登入、OAuth 跳轉與 Popup 補正常 GeckoView 功能；網站是否接受登入，必須以真實帳號在手機實測。尤其 Passkey/WebAuthn 仍未驗證。
- **Google OAuth**：Google 的 OAuth 政策限制由 App 開發者控制的 Embedded User-Agent；在部分情況下會明確回 `disallowed_useragent`。這是網站政策，不是單純更換 User-Agent 或開啟 Cookie 能保證解決的問題。Abwr 提供「在系統瀏覽器開啟」選項，讓使用者依網站正常方式登入。**它不會把系統瀏覽器 Cookie 自動複製回 Abwr**；第三方網站的 OAuth callback 是否能回到 Abwr 取決於該網站是否提供合法的回呼機制，不能宣稱一定成功。
- **Cloudflare／Turnstile**：官方明確指出 JavaScript、Cookie、封鎖腳本的擴充、嵌入式瀏覽器相容性與網路狀態都可能影響驗證。某些嵌入式瀏覽器不受支援。Abwr 只支援正常的視覺／網頁驗證流程，不自動解 CAPTCHA、不生成偽裝真人事件、不規避反機器人機制。
- **ETP**：GeckoView Enhanced Tracking Protection 預設開啟，可能影響個別網站資源。使用者可以手動測試關閉追蹤保護後重載網站；這只調整本機瀏覽器隱私設定，不影響伺服器判定。

官方參考：
- [Google OAuth 安全瀏覽器政策](https://developers.google.com/identity/protocols/oauth2/policies)
- [Google embedded webview 的 OAuth 限制](https://developers.googleblog.com/en/upcoming-security-changes-to-googles-oauth-20-authorization-endpoint-in-embedded-webviews/)
- [Cloudflare Challenge 解決問題排查](https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/)
- [Cloudflare 支援的瀏覽器](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)

## v0.6.1 已實作的相容性基礎（Source）

1. GeckoView 每個主分頁、額外分頁及 OAuth popup 使用同一種 `PromptDelegate`，顯示網頁 JavaScript alert、confirm、prompt、離開頁面確認、彈窗授權及文件重送確認。
2. Android `ACTION_OPEN_DOCUMENT` 系統檔案選擇器連接 Gecko `FilePrompt`（一個或多個檔案），由本人選檔，不把本機檔案路徑交給網頁 Agent。檔案選擇器的真機相容性仍待驗證。
3. 網站 `PermissionDelegate` 支援顯示網頁權限詢問，如第三方儲存空間存取／定位／通知； Android runtime location、camera、microphone 權限只有使用者明確同意後才可能 grant。未實作的媒體設備實體選擇仍應列為待改善。
4. App 選單「在系統瀏覽器開啟」提供人工使用其他已安裝瀏覽器的入口；不會偷換 UA、驗證憑證或自動轉移 Cookie。
5. WebExtension `action=compatibility` 回報當前頁面疑似 Cloudflare／Turnstile challenge、Google `disallowed_useragent`、目前 URL、load readyState、JS 是否有效等**觀察訊號**，不是挑戰破解器。
6. MCP `browser_compatibility`、HTTP `POST /v1/command {"action":"compatibility"}` 提供工具存取相同診斷。
7. 已有的 `/v1/status`、`browser_snapshot`、`browser_read`、`browser_takeover` 用來判斷登入進度及正常人工接管。

## 真機驗收（Device — 尚未通過）

分別測：普通 GitHub 登入／登出／重開 App 保持登入；GitHub OAuth popup 及主頁跳轉；Google 按鈕是否出現 Embedded User-Agent 拒絕及系統瀏覽器備援；Cloudflare Challenge 頁手動完成、同容器後續導航、重新載入與重啟後 Cookie 狀態。確認 **登入後可實際使用目標網站** 才是成功。

測試結果需記錄：
- 目標網址和測試類型，不收集帳號、密碼、原始 Cookie、2FA 碼
- UI 正常顯示、JS/Popup、跳轉後的主頁 URL
- 挑戰頁是否出現；若失敗，完整錯誤碼、可見診斷訊息（不要包含 Token）
- 是否能正常人工接管／交還 Agent
- 前景 / 背景差異

**SOURCE 已實作、CI PASS 和 DEVICE PASS 是不同狀態，不能互相代替。**
