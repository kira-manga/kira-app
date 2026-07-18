# Complaint feature production decision

Decision date: 2026-07-18
Current decision: keep the feature enabled for internal testing; treat it as a hard public-release
blocker until the server-side controls and disclosures below are verified.

## What ships in the client

Both Android and iOS expose the user complaint/feedback flow.

- Android uses the Firebase Firestore Android SDK against `complaints_v2`.
- iOS uses the Firestore REST API through Ktor against the same collection.
- A complaint can contain a persistent device/vendor identifier, category, subject, free-form body,
  status, timestamp, app/device metadata, and replies/closure metadata.
- User-side create, list, update, reply, and delete paths exist.
- Collection-wide list/status/edit/delete operations exist for the admin UI.
- `Admin.isAdmin` only reveals that UI in debug/dev binaries. It is not identity, authentication,
  authorization, or protection for Firestore endpoints.

There is no Firebase Authentication dependency or authenticated REST bearer token on this path.
Mobile Firebase API keys identify the project but are public client configuration, not credentials.
No deployed Firestore rules are present in this repository, so their actual production behavior
cannot be proven here.

## Security and privacy findings

The following remain unresolved for public distribution:

1. A client can claim a device/user identifier. Client-side filtering by that value does not prove
   ownership.
2. The iOS REST client is intentionally unauthenticated. Android SDK calls also have no app user
   identity unless deployed rules use another verified mechanism.
3. The release admin UI is hidden, but an external caller is not constrained by Compose navigation.
4. Read-all, update, and delete safety depends entirely on deployed Firestore rules that are not
   versioned or tested in this workspace.
5. App Check enforcement, abuse/rate limits, payload-size limits, and spam/moderation handling are
   not demonstrated.
6. Retention periods, operator access, user deletion handling, incident response, and takedown/legal
   ownership are not confirmed.
7. Free-form text can include personal or sensitive information despite UI guidance.

Consequence: the complaint feature must not be called production-secure merely because the admin
screen is debug-gated or because an API key is present.

## Client hardening completed

- Removed a previously embedded Firebase project/API-key pair from common source.
- iOS reads project ID/API key from the real, gitignored `GoogleService-Info.plist`; Desktop test
  plumbing uses environment values.
- Runtime configuration and Firestore document IDs are validated before I/O.
- The REST client never adds a misleading `Authorization` header.
- Logs no longer include raw request URLs, API keys, document IDs/names, user IDs, subject/body,
  timestamps, raw response bodies, server error payloads, or exception messages.
- Android Firestore logs were reduced to generic operation/status messages; raw document maps and
  user/subject identifiers were removed.
- iOS no-op analytics/crash adapters no longer print event parameters, user IDs, custom-key values,
  exceptions, or context maps.
- The app privacy manifest declares Other User Content, Device ID, and diagnostic data for app
  functionality; Firebase SDK manifests still need archive-level review.

Focused tests cover injected configuration, no Authorization header, user and paginated admin reads,
validated update/delete document IDs, missing configuration failing before network I/O, and server
error bodies not escaping into exceptions.

## Requirements to remove the blocker

Before a public build, the owner must choose and verify one path:

### Path A — secure and retain the feature

1. Version Firestore rules in an owner-controlled security repository and deploy them to the exact
   Firebase project used by both app configs.
2. Introduce a non-spoofable user identity/ownership model. Do not authorize from a caller-provided
   `userId` field.
3. Authorize admin operations server-side using verified claims/roles; never trust `Admin.isAdmin`.
4. Test rules in the Firebase emulator and against a staging project for create/read-own/update-own/
   delete-own, cross-user denial, list-all denial, admin allow, malformed payload denial, and size/
   rate abuse.
5. Decide and enforce App Check, rate limiting, payload limits, spam handling, and moderation.
6. Set retention and deletion SLAs; provide an owner-operated support/deletion process and verify
   that deletion covers complaint text and associated identifiers/metadata.
7. Finalize the privacy policy and both store privacy disclosures from actual console settings.
8. Run Android and iOS device E2E against production-equivalent rules and retain evidence.

### Path B — disable for the public build

Remove all public navigation to complaint/feedback submission and do not initialize or call the
complaint data path. Re-run privacy/store disclosure review so disabled code is not represented as
active collection. Keeping source code compiled but unreachable is acceptable only if no automatic
collection or background call remains.

## Release gate

For internal builds, mark every complaint test dataset as disposable and avoid real personal data.
For public builds, this gate is binary: Path A is evidenced as complete, or Path B is implemented.
An undocumented acceptance of the current risk is not an approved release outcome.
