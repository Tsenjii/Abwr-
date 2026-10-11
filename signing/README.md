# Abwr production signing

The production package remains `dev.local.aibrowser`. Distribution APKs must use the certificate pinned in `certificate-sha256.txt`; release validation refuses a missing or different signing identity.

Set repository Actions Secret `ABWR_SIGNING_KEY_B64` to the existing JKS key in base64. The recovered v0.9 key uses alias `androiddebugkey` and store/key passwords `android`. Do not generate a replacement. Optional secrets `ABWR_STORE_PASSWORD`, `ABWR_KEY_PASSWORD`, `ABWR_KEY_ALIAS` override these defaults only for the same pinned certificate.

The CI-cache fallback exists only to migrate the recovered key. It is not durable key storage. After the Secret is configured, rerun the workflow and verify `Signing key restored from repository secret`, a matching APK certificate, and success of the release guard. Then remove the temporary migration export and cache fallback.

A private owner backup has been preserved separately. Its `install_secret.py` checks the certificate and sends key bytes to an already authenticated owner's `gh secret set` through stdin without printing the key. No private keystore or base64 key belongs in this repository, issues, logs, or public release assets. The encrypted one-time envelope is readable only with its separate recovery private key.

Developer APKs use `dev.local.aibrowser.dev` and cannot replace production Abwr. If an older installed version used a different lost key, the v0.9 key cannot reconstruct it. An update must preserve the installed package's signing identity and data.
