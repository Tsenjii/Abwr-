---
name: abwr-browser
description: Use Abwr to operate the user's authorized Android GeckoView browser through local MCP or HTTP, including reading pages, forms, tabs and groups, screenshots, independent cookie containers and human takeover.
---

# Abwr Browser — Agent-independent Skill

This skill works with **any agent capable of running a local MCP stdio client or sending authenticated HTTP requests**. It is not limited to OpenCode. Compatible workflows include terminal agents, custom Android agents, tool-calling assistants and MCP clients.

## v0.11 connection and document workflow

- Update the local repo and `bridge/mcp_stdio.py`, then restart the MCP client after an APK update. Check installed version `0.11.0`/code `20`, API version `0.11.0`, connected `contentBridgeVersion:0.11.0`, schema `3`, MCP serverInfo `0.11.0` and 59 discovered tools. Treat mismatches as a mixed installation. Read `installedApplication.sourceRevision` in diagnostics and compare it with the tested source in `docs/V0_11_TAB_GROUPS_BACKGROUND.md`.
- Read capabilities and status before using new tools. Wait for `extensionPortReady:true` and `documentTransition:false`; treat `pendingUrl` as a requested destination, not the committed page.
- Preserve the returned profile, activeTab, URL and documentId. Pass `profile`, `tab`, `expectedUrl` and `documentId` on writes. Inspect status and refresh snapshots after PAGE_CHANGED; do not retry the old write automatically.
- Parse `browser_snapshot` as JSON. Read `result.snapshot` for text, `result.elements` for refs/labels/rects and `result.frames` for child documents. Scope refs to the frame that produced them; each new snapshot of that frame replaces its refs.
- Use `browser_frames` and frame metadata to identify child documents. Pass the exact `frame` ID and optionally `expectedFrameUrl` on actions in that child. Do not guess a frame index or call an incomplete snapshot a full-page inspection. Use pagination/filters for large pages; closed shadow roots remain opaque.
- Use `browser_press` for native keyboard combinations such as Ctrl+A, Backspace or Enter; optionally focus a target first. Use `browser_click_point` after a new screenshot when DOM/frame actions cannot reach an ordinary control. Pass imageWidth/imageHeight and screenshot coordinates; use only the currently rendered profile. Read the actual resulting page after any `dispatched:true` response.
- Perform ordinary login/navigation controls within the user's scope using DOM, frames and native input. Ask the owner only for required identity/permission/verification steps; never interpret a possible/unknown challenge state as successful sign-in.
- Verify a live screenshot's document, dimensions and visible content. Keep `/last` clearly marked cached. Read `docs/V0_11_TAB_GROUPS_BACKGROUND.md` for current CI/device evidence and `docs/V0_11_ACCEPTANCE_PROMPT.md` when asked to accept the build.
- For a custom checkbox/radio with `visible:false,interactable:true,interactionProxy.reason:associated_visible_label`, use its original input ref with click/toggle. Check the actual returned `checked` state. Use toggle's explicit desired state for idempotent changes; never turn an unrelated hidden or disabled input into an interactable target.
- Keep background and inactive-container DOM/screenshot requests scoped with `profile`; these requests do not select the UI container. Native keyboard/coordinate input still requires the active rendered profile. On screenshot timeout inspect diagnostics and current document; never substitute cached pixels as live or reload a signed-in document automatically.

## Reliable operation

- Call `browser_capabilities`, then `browser_status({profile})` and `browser_tabs({profile})`; keep the returned profile, activeTab and URL together.
- Pass `profile`, `tab` and `expectedUrl` on page actions when the page must stay unchanged. These fields check the current selection; they do not select a tab. On PAGE_CHANGED or STALE_REF, inspect status and request a new snapshot before acting.
- Wait for bounded automatic reconnect on BRIDGE_NOT_READY / BRIDGE_DISCONNECTED, then refresh the snapshot. Do not reload signed-in pages unless recovery fails and reloading fits the task.
- Treat WAIT_TIMEOUT as a failed wait. On any timeout or connection loss after a write, inspect its visible effect before retrying; do not duplicate a click, submit or purchase.
- Use MCP cancellation to discard queued operations. Already-issued HTTP/page effects may finish; cancellation does not undo them. Status, diagnostics and takeover remain in the separate control lane.
- Use `browser_type` / `browser_clear` for plain contenteditable editors; verify complex editors after input. Use `browser_downloads` for metadata; starting/exporting downloads and file uploads remain owner-driven UI actions.


## Connection

- On the Android device: Abwr App must be running; loopback server at `http://127.0.0.1:8765`.
- Unauthenticated liveness: `GET /health`.
- Authenticated capabilities: `GET /v1/capabilities`.
- All other API calls: `Authorization: Bearer $AI_BROWSER_TOKEN`. The user copies this token from Abwr on the phone; never print, export, commit or send it to a third-party website.
- MCP adapter: run `python3 bridge/mcp_stdio.py` as a local stdio process with `AI_BROWSER_HOST` and `AI_BROWSER_TOKEN`. Requires only Python standard library. MCP exposes discoverable tools through `tools/list`.
- For a computer client connected through the user's authorized ADB, run `adb forward tcp:8765 tcp:8765`, then use the computer's `http://127.0.0.1:8765`. Installing the APK does not configure an AI client's MCP server automatically. Verify a real MCP tool call separately from HTTP liveness.

A remote agent must use a user-authorized secure transport to the Android device; never publish this loopback API directly to a network interface.

## Efficient browser workflow

1. Call `browser_status` and `browser_tabs` to discover the selected profile and tab.
2. `browser_navigate(url, waitForLoad=true)`. Verify the completed documentId/URL and actual page; `readyState:complete` does not prove HTTP 200 or successful login.
3. Prefer `browser_read(maxChars=9000)` for prose/content retrieval; use `browser_snapshot` only for interactive controls, or `browser_dom` / `browser_find` for detailed inspection.
4. Use `browser_click({target:"e..._..."})` or a scoped CSS selector. Snapshot refs expire after the next snapshot or page navigation; reacquire if stale.
5. Use `browser_type`, `browser_clear`, `browser_select`, `browser_wait`, `browser_find_in_page`, `browser_back`, `browser_reload` as appropriate. Always verify visible results after a write operation.
6. If HTML information is insufficient, `browser_screenshot` requires a live compositor. The cached `browser_screenshot_last` is an **old frame**; compare the timestamp using `browser_screenshot_meta`.
7. For troubleshooting, `browser_diagnostics`, `browser_devtools` and `browser_storage_inspect` offer status/page-limited information.

## Ordinary browser controls

- Profiles are independent Gecko storage contexts: `browser_profiles`, `profile_create`, `profile_switch`. Same-profile tabs share that profile's normal website sign-in state.
- `browser_tabs` / `browser_tab_new` / `browser_tab_select` / `browser_tab_close`.
- Discover groups with `browser_tab_groups`; create/update/move/ungroup with `browser_group_create`, `browser_group_update`, `browser_group_move`, `browser_group_ungroup`. Pass discovered same-profile tab IDs as a string array. Moving only changes group membership, and ungrouping keeps the tabs. Preserve manual takeover and reject foreign-container IDs; read `docs/API.md` for exact arguments.
- `browser_bookmarks`, `browser_bookmark_add`, `browser_history`, `browser_settings`, `browser_desktop_mode`, `browser_search_engine`.
- Android UI offers URL/search bar, homepage, tab picker, bookmarks, history, find-in-page, desktop page mode, trackers protection and opening external links.

## Credentials, permissions, takeover

- Websites already signed into an Abwr profile can be operated there within the user's requested scope without extracting cookies.
- `cookie_metadata` only lists document-visible cookie **names**; `storage_inspect` redacts suspected secrets. Do not extract login tokens or attempt to access browser profiles of other Android apps.
- During manual takeover, the Agent must not continue writing to the page; wait for the user to return control. Sensitive actions such as changing account credentials, deleting data, or making purchases need explicit confirmation.
- Human/2FA/passkey verification should use the site's normal flow. Do not impersonate humans or bypass site challenges.

## What is NOT guaranteed

- The private GeckoDisplay/ImageReader renderer is not proven to provide continuous live background screenshots on every Android device, lock-screen or OEM battery configuration.
- Google OAuth/Passkey compatibility and cookie persistence must be validated on-device.
- This build does not provide a Chrome DevTools Protocol debugger, full network trace, arbitrary automated file upload, automatic download resume, or Chrome/Brave profile import. Local downloads are available through the owner-controlled UI and still need device validation.
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


## v0.6.1 — Login compatibility / GitHub / Google OAuth / Cloudflare

- Before a login-sensitive workflow, call `browser_compatibility`. It only reports visible page indicators and can be wrong; never claim a challenge passed unless the logged-in destination and requested action are verified.
- If a Cloudflare or Turnstile challenge appears, allow the user to complete the website's normal verification in Abwr's visible browser, then re-read the page. Do not automate challenge solutions or circumvent browser integrity/identity checks.
- If Google's OAuth endpoint rejects an embedded browser (e.g. `disallowed_useragent`), do not change fingerprint or spoof UA to defeat that restriction. The Abwr UI has a user-initiated **open in system browser** menu item. Authentication in another browser does not automatically share cookies with Abwr.
- Website JavaScript alerts/confirms, popup permissions, file selection and browser permissions are surfaced through the Android UI. The user grants device permissions. Agents must not auto-approve them.
- Web storage is scoped to the current Abwr profile; third-party storage access may require the user to approve a website prompt.
- Check the current build/device evidence for each login provider separately. A successful GitHub flow does not prove Google or Cloudflare compatibility; see `docs/LOGIN_COMPATIBILITY.md` for the checklist.
