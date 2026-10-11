---
name: abwr-browser
description: Control the user's authorized Android GeckoView browser through local MCP or HTTP. Supports multi-container sessions, native navigation, screenshots, page reading, tab management and human takeover.
---

# Abwr Browser — Agent-independent Skill

This skill works with **any agent capable of running a local MCP stdio client or sending authenticated HTTP requests**. It is not limited to OpenCode. Compatible workflows include terminal agents, custom Android agents, tool-calling assistants and MCP clients.

## Connection

- On the Android device: Abwr App must be running; loopback server at `http://127.0.0.1:8765`.
- Unauthenticated liveness: `GET /health`.
- Authenticated capabilities: `GET /v1/capabilities`.
- All other API calls: `Authorization: Bearer $AI_BROWSER_TOKEN`. The user copies this token from Abwr on the phone; never print, export, commit or send it to a third-party website.
- MCP adapter: run `python3 bridge/mcp_stdio.py` as a local stdio process with `AI_BROWSER_HOST` and `AI_BROWSER_TOKEN`. Requires only Python standard library. MCP exposes discoverable tools through `tools/list`.

A remote agent must use a user-authorized secure transport to the Android device; never publish this loopback API directly to a network interface.

## Efficient browser workflow

1. Call `browser_status` and `browser_tabs` to discover the selected profile and tab.
2. `browser_navigate(url, waitForLoad=true)`. A load-stop event does **not** prove HTTP 200; check the actual resulting URL/title.
3. Prefer `browser_read(maxChars=9000)` for prose/content retrieval; use `browser_snapshot` only for interactive controls, or `browser_dom` / `browser_find` for detailed inspection.
4. Use `browser_click({target:"e..._..."})` or a scoped CSS selector. Snapshot refs expire after the next snapshot or page navigation; reacquire if stale.
5. Use `browser_type`, `browser_clear`, `browser_select`, `browser_wait`, `browser_find_in_page`, `browser_back`, `browser_reload` as appropriate. Always verify visible results after a write operation.
6. If HTML information is insufficient, `browser_screenshot` requires a live compositor. The cached `browser_screenshot_last` is an **old frame**; compare the timestamp using `browser_screenshot_meta`.
7. For troubleshooting, `browser_diagnostics`, `browser_devtools` and `browser_storage_inspect` offer status/page-limited information.

## Ordinary browser controls

- Profiles are independent Gecko storage contexts: `browser_profiles`, `profile_create`, `profile_switch`. Same-profile tabs share that profile's normal website sign-in state.
- `browser_tabs` / `browser_tab_new` / `browser_tab_select` / `browser_tab_close`.
- `browser_bookmarks`, `browser_bookmark_add`, `browser_history`, `browser_settings`, `browser_desktop_mode`, `browser_search_engine`.
- Android UI offers URL/search bar, homepage, tab picker, bookmarks, history, find-in-page, desktop page mode, trackers protection and opening external links.

## Credentials, permissions, takeover

- Websites already signed into an Abwr profile can be operated there within the user's requested scope without extracting cookies.
- `cookie_metadata` only lists document-visible cookie **names**; `storage_inspect` redacts suspected secrets. Do not extract login tokens or attempt to access browser profiles of other Android apps.
- During manual takeover, the Agent must not continue writing to the page; wait for the user to return control. Sensitive actions such as changing account credentials, deleting data, or making purchases need explicit confirmation.
- Human/2FA/passkey verification should use the site's normal flow. Do not impersonate humans or bypass site challenges.

## What is NOT guaranteed

- The experimental private VirtualDisplay is not proven to provide continuous live background screenshots on every Android device.
- Google OAuth/Passkey compatibility and cookie persistence must be validated on-device.
- This build does not provide a Chrome DevTools Protocol debugger, full network trace, reliable download/upload manager, or Chrome/Brave profile import.
- Native Gecko find-in-page and desktop UA/viewport support are browser capabilities, not techniques for evading site rules.

## API examples

```bash
curl http://127.0.0.1:8765/health
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" http://127.0.0.1:8765/v1/capabilities
curl -H "Authorization: Bearer $AI_BROWSER_TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"read","profile":"G001","maxChars":6000}' \
  http://127.0.0.1:8765/v1/command
```

Refer to `docs/API.md` for routes and `docs/PROGRESS.md` for honest CI/device validation.
