# Android Internal Testing release

This repository contains a fail-closed reusable workflow at
`.github/workflows/android-internal-testing.yml`. Start it through the `Internal Testing Release`
workflow on `internal-testing`, then select `android` or `both`. A push alone does not publish.

## Audit result

- Application id: `me.manga.kira`; app name: Kira Manga.
- No release/upload keystore was found in the repository, `/Users/abdelrahman/Private`,
  `/Users/abdelrahman/.android`, or `/Users/abdelrahman/Downloads`. The only local keystore is the
  Android debug keystore and it must not be used for Play.
- GitHub Actions currently has no Android signing, Firebase, Play service-account, or source-pin
  secrets. The verified public source origin is stored as the repository variable
  `KIRA_SOURCE_CONFIG_BASE_URL=https://api.kiramanga.me`.
- `https://api.kiramanga.me/api/v1/sources` is reachable over HTTPS. The document route currently
  returns 404 because the backend has no published snapshot yet. The workflow intentionally refuses
  to build until that route returns a signed document.
- The three local source-delivery commits were reviewed and pushed to
  `production-hardening-source-signing`. The branch is still separate from `internal-testing` until
  the owner is ready to trigger the new workflow.

## Required values

| Value | Where it comes from / how to obtain it | Format and sensitivity | GitHub storage |
|---|---|---|---|
| `KEYSTORE_FILE` | The workflow derives this as `$RUNNER_TEMP/kira-upload.jks`; it is not a repository value. Locally, it is the path to the upload keystore. | Absolute file path; non-secret local/runner metadata. | Not stored. |
| `KEYSTORE_PASSWORD` | The password chosen when creating the upload keystore. | String; secret. | `ANDROID_KEYSTORE_PASSWORD` secret. |
| `KEY_ALIAS` | The alias chosen during `keytool -genkeypair`; this workflow expects the alias to be supplied separately. | `[A-Za-z0-9._-]+`; not cryptographic secret, but stored with release credentials. | `ANDROID_KEY_ALIAS` secret. |
| `KEY_PASSWORD` | The key-entry password chosen during `keytool -genkeypair`. | String; secret. | `ANDROID_KEY_PASSWORD` secret. |
| `KIRA_SOURCE_CONFIG_BASE_URL` | The backend public origin. The app appends `/api/v1/source-config/document`; it must not include `/api/v1`, query, fragment, credentials, or a trailing-path assumption. | Credential-free `https://host`; public but operationally important. | `KIRA_SOURCE_CONFIG_BASE_URL` repository variable. It is already set to `https://api.kiramanga.me`. |
| `KIRA_SOURCE_CONFIG_PINNED_KEYS` | The backend signing ceremony produces `<key-id>.public.b64`. The app pins that X.509 Ed25519 public key. | `key-id=Base64-X.509[,key-id=Base64-X.509]`; public trust material, not a private secret. | `KIRA_SOURCE_CONFIG_PINNED_KEYS` repository variable after the backend key ceremony. |

The workflow also requires these release inputs:

- `GOOGLE_SERVICES_JSON`: the real Firebase file from Firebase Console → Project settings → Your
  apps → Android app → `google-services.json`. The repository convention is one-line Base64:
  `base64 < app/google-services.json | tr -d '\n'`. Store it as an Actions secret named
  `GOOGLE_SERVICES_JSON` in the `google-play-internal` environment; it is configuration/credential
  material and must remain gitignored.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: the complete JSON key for the Play-only service account,
  stored verbatim as an Actions secret in the `google-play-internal` environment. Fastlane consumes
  it through `json_key_data` and never writes it to the workspace.
- `ANDROID_BUILD_NUMBER_OFFSET` (optional): a non-negative repository variable, default `1000`.
  The workflow computes `offset + github.run_number`, so every workflow run gets a higher Play
  `versionCode` without modifying the development fallback in `release/version.properties`.

## Upload key ceremony

First check Play Console → **Test and release → App bundle explorer** and **Test and release →
App integrity → Play app signing**. If Kira already has an upload certificate, use that existing
keystore; do not generate a replacement. Internal-track history is private, so local files and the
public Play listing cannot prove that no previous upload exists.

If Play confirms that `me.manga.kira` has never been uploaded, create the key outside the repository:

```bash
mkdir -p "/Users/abdelrahman/Private/kira-signing"
chmod 700 "/Users/abdelrahman/Private/kira-signing"
keytool -genkeypair -v -storetype PKCS12 \
  -keystore "/Users/abdelrahman/Private/kira-signing/kira-upload.jks" \
  -alias kira-upload -keyalg RSA -keysize 4096 -validity 10000
```

Back up the keystore and both passwords in an encrypted password manager/secret vault, with a
second recovery copy controlled by the owner. Never email it, commit it, or put it in a build
artifact. Convert it for GitHub with:

```bash
base64 < "/Users/abdelrahman/Private/kira-signing/kira-upload.jks" \
  | tr -d '\n' > "/Users/abdelrahman/Private/kira-signing/kira-upload.jks.b64"
```

Paste that one-line value into the `ANDROID_KEYSTORE_BASE64` Actions secret in the
`google-play-internal` environment. The runner decodes it to `$RUNNER_TEMP/kira-upload.jks` with
mode `0600` and deletes it in the final cleanup step.

## Source signing ceremony

The backend owns the private key. From `kira-backend/`, the documented command is:

```bash
scripts/signing/generate-key.sh prod-YYYY-NN .secrets/signing
```

This creates `<id>.private.der/.private.b64` and `<id>.public.der/.public.b64` with restrictive
permissions. Keep the private files in the deployment secret manager; never commit or print them.
Install the private value in the backend only through the protected secret procedure documented in
`kira-backend/docs/SOURCE_DOCUMENT_SIGNING.md`. The backend startup signer cross-checks the private
PKCS#8 key against the configured X.509 public key before serving documents.

Set the app variable from the generated public file, for example:

```text
KIRA_SOURCE_CONFIG_PINNED_KEYS=prod-2026-01=<contents-of-prod-2026-01.public.b64>
```

The final verification is: backend `/api/v1/source-config/document` returns a signed document whose
`X-Config-Signing-Key-Id` is present in the app variable, and the backend's startup signer has
verified the private/public match. The workflow performs the key-id/header check and Gradle validates
the Base64 X.509 Ed25519 serialization.

## Play Console service account

Create the app manually in Play Console as **Kira Manga**, package `me.manga.kira`, then create the
**Internal testing** track. In Play Console → **Account details**, note the linked Google Cloud
Project. In Google Cloud Console, enable **Google Play Android Developer API**, create a dedicated
service account such as `kira-play-internal`, and copy its email. In Play Console → **Users and
permissions**, invite that email with app-only access to Kira and grant only:

- **View app information (read only)**
- **Release apps to testing tracks**

Add **Manage testing tracks and edit tester lists** only if the workflow will manage tester lists;
this workflow does not. Do not grant production release, financial, store-presence, or admin access.

For the selected Fastlane integration, create a JSON key in Google Cloud → Service Accounts → the
service account → **Keys → Add key → Create new key → JSON**. Store the complete JSON as
`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` in GitHub **Settings → Environments → google-play-internal →
Environment secrets** (or the repository Actions secrets page if the environment is not yet
configured). Do not commit it or upload it as an artifact. Fastlane 2.235.0 is already
pinned in `Gemfile.lock`; the workflow validates the JSON before building and does not add a
marketplace Play-upload action. It reuses the repository's existing official checkout, Java,
Gradle, Ruby, and artifact actions with the same major-version pins as the TestFlight workflow;
the workflow grants only `contents: read`.

The first Play app setup and Play App Signing acceptance may require a one-time manual Console
action. After that, open GitHub Actions, choose `Internal Testing Release`, select the
`internal-testing` branch and the Android platform, and run the workflow.

## Secure local configuration and helpers

The local file to fill in is:

```text
/Users/abdelrahman/Projects/kira/Kira manga/.secrets/android-release.env
```

It is already ignored by `.gitignore`. Keep it mode `0600`:

```bash
chmod 600 "/Users/abdelrahman/Projects/kira/Kira manga/.secrets/android-release.env"
```

The committed [`.env.android-release.example`](../../.env.android-release.example) explains every
assignment. The real file contains the Android upload values (`ANDROID_KEYSTORE_*`), the Base64
Firebase value (`GOOGLE_SERVICES_JSON`), the raw Play service-account JSON, the public URL and pin,
the backend Ed25519 key ID/files, the bundled document file, and local admin credentials used only
for an explicitly requested source import. File references such as `ANDROID_KEYSTORE_FILE`,
`GOOGLE_SERVICES_JSON_FILE`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE`,
`SOURCE_CONFIG_PRIVATE_KEY_FILE`, and `SOURCE_CONFIG_PUBLIC_KEY_FILE` are preferred over inline
binary, JSON, password, or private-key values.

Backend signing is deliberately split: `SOURCE_CONFIG_PRIVATE_KEY_FILE` / `KIRA_SIGNING_PRIVATE_KEY`
is secret-manager-only backend material; `SOURCE_CONFIG_PUBLIC_KEY_FILE` becomes the Android
`KIRA_SOURCE_CONFIG_PINNED_KEYS` trust root; `SOURCE_CONFIG_KEY_ID` must equal both backend key-ID
variables. The backend signing helper installs the four documented `KIRA_SIGNING_*` secrets. The
`SOURCE_CONFIG_DOCUMENT_FILE` is the JSON body for the documented `POST /api/v1/admin/sources/import-bundled`
on-ramp; importing it makes the backend allocate the revision, timestamp, checksum, previous-chain
metadata, and detached signature. There are no safe manual values for those snapshot fields—do not
invent or place them in the local configuration.

Validate without printing values:

```bash
scripts/release/validate-android-release-config.sh \
  .secrets/android-release.env
```

The apply helper is intentionally not automatic. It first runs the validator, then can stream
GitHub environment secrets to the existing `google-play-internal` environment, set public
repository variables, and invoke the backend signing-secret installer:

```bash
scripts/release/apply-android-release-config.sh \
  .secrets/android-release.env --confirm-apply
```

Adding `--publish-source-config` additionally logs in with the local admin credential, imports the
configured document, and requires the public endpoint to return HTTP 200 with the expected signing
key ID. Do not use that option until the backend deployment and key ceremony are complete. Neither
helper is run by CI, and this setup has not run the apply helper.

Never share the real file or referenced files, commit them, upload them as artifacts, paste them in
issues, or expose them in shell traces/logs. The helper scripts report names and statuses only; they
never print passwords, private keys, JSON bodies, tokens, or Base64 values.
