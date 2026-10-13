# v0.14.1 completion work — next AI start here

User priority: finish every feasible requirement, including compilation, before returning. The supplied G1–G20 scope supersedes older plans. AI and UI share ordinary browser permissions. Explicit takeover pauses writes; owner-authorized API may explicitly return. Never automate identity puzzles, fabricate fingerprint isolation, export secrets, or replay uncertain writes. Main publication is authorized; use the pinned certificate and preserve installed data.

## Current source

App/API/content/MCP/SDK/examples: **0.14.1, versionCode 24, schema 4, 82 tools**. Private-profile routing uses a fixed built-in Gecko API to read the actual tab's `unsafeSessionContextId`. Two private profiles may share public `firefox-private` while retaining different native contexts. The fixed getter exposes no generic evaluation or site JS and changes no Gecko security preference. Missing identity fails closed. Private proxy configuration remains memory-only; no private state, account value or password enters audit output.

Added bounded concurrency measurement after the 15 sequential fixture profiles. One then two independent profile workers perform an ABWR event-pump delay and DOM read for ten rounds. Report measured bridge readiness, queue snapshots, errors, latency and resident sessions. BUSY lowers recommendation; no write is replayed. `writeConcurrencyProven:null` is deliberate: read capacity is not a promise of concurrent native input.

Added Android API 33 (Android 13) alongside API 35. Main tests use the actual pinned release signing key and first install known v0.14.0 APK SHA `8c066ab055e3597995b1f3679b97f433a29517b79981ab6748b6c2b1b2bf3cf9`. On a fresh rooted Actions emulator only, the upgrade case seeds two fixture profiles, distinct persistent HttpOnly cookies, localStorage, bookmarks, tabs and explicit takeover. It installs v0.14.1 with `adb install -r` and proves preservation of certificate, control token and saved context IDs. No uninstall, data clear or physical-device installation is performed by CI.

## Verification state

First candidate run **38401285955 FAILED**, source 41ce700522e2d93b23c81ff7b4b3cdb46163e59b. Android 13 UI test expected a group card after it was already inside the group; corrected to inspect the visible state before acting. Android 15 reached private proxy tests but the test called the private-profile creation endpoint with GET instead of POST; corrected to send `{}`. Normal first-request native context routing was observed; one/two-profile read work passed ten rounds each. Private proxy success is not claimed from this failed run. Evidence and complete failures: `evidence/V0_14_1_ATTEMPT1.json`, artifacts 11815973784 (API33), 11816044048 (API35). Independent new-primitives cases now still run after an earlier UI failure; the overall failure gate remains before the long idle test.

- Local: Python 66 and six JS suites passed; first candidate Kotlin JVM 63 and Debug compilation passed on Actions. New lifecycle/process diagnostic edits require a fresh build.
- New Python checks, Kotlin compilation, release signing and API 33/35 device runs: **PENDING** until actual Actions artifacts are recorded here.
- Previous fully green **v0.14.0** product evidence is preserved in `V0_14_BUILD_RESULT.md`, run 38386576654, source 8b86c094775f7911a4e707cb1a10abf90c358eae: 22/22 cases, HTTP/MCP 100 each, new JPEG/doc after 1/10/30 minutes screen off. Those results do not prove v0.14.1.
- SHARP R8s physical phone, real independent upstream exits, live third-party account/OAuth/DRM: **NOT_RUN**. No phone transport, adb or configured exits is available in this workspace. A CI emulator is not a phone certificate.

## Commands and evidence

Full phone-only commands and output contracts: [Termux evidence guide](V0_14_1_TERMUX_EVIDENCE.md). Added foreground/background/locked 60/600/1800-second self-contained fixture harness and configured HTTPS exit validation; actual physical execution is still required. Diagnostics exposes real processId and a process-wide instance marker. Durable GitHub prereleases preserve verified APKs/evidence; the known predecessor is SHA-pinned before release and upgrade testing, avoiding Actions artifact expiry. No existing tag, APK or release is replaced.

Termux (stdlib SDK, no Playwright import): `python3 scripts/termux_acceptance.py --create-test-profiles --profiles 15 --cleanup-created --concurrency-rounds 10`. Requires App 0.14.1 and matching bridge, `AI_BROWSER_HOST=http://127.0.0.1:8765`, token in environment only. Uses fixture port 8877 and saves owner-only ledger under `~/.workbuddy-ai/ledger`, images under `shots`, quota journal under `state`. It restores selection and only removes profiles created by this run.

CI uploads `automation-private-proxy.json`, `automation-concurrency.json`, `signed-upgrade.json`, `results.json`, installed source/certificate, raw native crash and exit-info evidence, screenshots and elapsed 60/600/1800-second checkpoint metadata. Artifact names include Android API to avoid collisions. Record source, workflow run, job IDs, APK SHA/certificate, artifact digests and failures before promoting completion.

Keep `AGENTS.md`, `PROGRESS.md`, `HANDOFF.md`, `AI_HANDOFF_2026-10-13.md`, `API.md`, the browser skill and this file consistent. Do not repeat old implementation work merely because a transient checkout is stale: fetch main and inspect merged PRs #4/#5 first.
