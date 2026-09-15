# Reviewed runtime archive inputs

`verified-tools.json` pins the named archives consumed by
`scripts/release/install-toolchain-inputs.py`. The mandatory bootstrap verifier
also binds that installer, its workflow placement, and the existing Action,
wrapper, Gradle graph, XcodeGen and SwiftPM inputs. The reviewed checkout and
GitHub-hosted OS remain the trust root: `/usr/bin/python3`, `/usr/bin/ruby`, curl,
system libraries and the selected Xcode are not independently attested here.

## Installation and consumption

- **Java 21.0.12.1+1:** select the exact Temurin Linux/x64 or macOS/ARM64 archive;
  check size/SHA-256 before parsing or extracting it into a fresh private
  directory. Compare the complete extracted tree with the authenticated archive,
  discover its unique `bin/java`, then check the runtime version before exposing
  `JAVA_HOME`/`PATH`. `setup-java` and its preinstalled-cache fallback are not used.
- **Ruby 3.3.12:** the official Ruby-builder archives embed
  `/opt/hostedtoolcache/Ruby/3.3.12/x64` or
  `/Users/runner/hostedtoolcache/Ruby/3.3.12/arm64`. Authenticate the archive before
  accepting that entire existing tree; extra/changed files and links or unsupported
  file modes fail closed. Only an absent prefix may be installed. Never overwrite
  or delete an unmatched preinstallation. The pinned `setup-ruby` action follows immediately
  with `self-hosted: true`, forcing cache-only runtime selection, and continues to
  install the already-locked Bundler/Fastlane dependencies. A hosted image with a
  provisioning difference outside the policies below is a failure, not an excuse
  to ignore files.
  One explicit alternative is allowed for the canonical preexisting Ubuntu24.04
  GitHub-hosted cache: the entire authenticated path/type/size/hash/link inventory
  must match, every regular file must have mode0777 with no special bits, and
  every directory (including the prefix root) must have mode01777. This validates
  the documented hosted permission transformation, not archive-mode identity;
  mixed or other modes fail, and the borrowed tree is never changed. The ordinary
  exact-archive route requires no privileged directory modes; both routes reject
  special bits on regular files and links. macOS has no transformed-mode route.
  Refusals report bounded difference counts and at most eight ASCII relative names
  already present in the authenticated archive; extra cache names are withheld.
  An unavailable tree comparison is reported as unknown, never as zero mismatches.
- **Apple Kotlin/Native 2.4.0:** authenticate the official GitHub release archive,
  LLVM `21-aarch64-macos-essentials-97` and libffi `3.3-1-macos-arm64`. Use a fresh
  Native home and fresh `KONAN_DATA_DIR`, selected with the exact Gradle project
  properties `kotlin.native.home` and `konan.data.dir`. Populate `.extracted` only
  for the two authenticated dependency trees. The sole intentional provider-file
  edit changes `airplaneMode = false` to `true` in `konan/konan.properties`; record
  its before/after hashes. No other dependency archive/cache is reused, and a
  missing dependency must fail instead of downloading. The post-policy tree is
  deliberately **not** described as byte-identical to the upstream archive.

Tar paths, links, member types and expansion bounds are checked before extraction.
Directory SGID metadata is stripped before extraction; setuid/sticky bits anywhere
and all privileged file/link modes remain forbidden. Regular-file bytes and modes
are not normalized.
Installer-owned temporary directories and newly created Ruby prefixes are removed
by the final `always()` step. Preexisting verified Ruby prefixes are preserved.
Gradle Action caches remain explicitly read-only: its pinned post hook otherwise
may execute Gradle after the final step has removed `JAVA_HOME`.

## Provider authority and limits

The Ruby and Temurin pins come from the SHA-256 asset digests on their official
GitHub releases; Native's official release digest agrees with its checksum asset.
The two direct JetBrains dependency pins come from their `.tar.gz.sha256` files.
Exact URLs, versions, digests and known sizes are in the registry. This is not
authority for a differently packaged Maven Native archive. Do not add or replace
Gradle verification entries by assuming GitHub/Maven archive equivalence.

The Ruby cache contract is from `ruby/setup-ruby` commit
`bec3f19a76460dbe12f60def7d1a77585f07516c` (`common.js`, `ruby-builder.js`). Native's
offline/property contract is from Kotlin 2.4.0 commit
`add726ca8c82922b6ab4cb2a27ae738d6a780817`, particularly `DependencyProcessor.kt`.
Gradle Action post behavior is from
`ed408507eac070d1f99cc633dbcf757c94c7933a` (`sources/src/caching/caches.ts`).

The Ubuntu cache permission policy is from `actions/runner-images` commit
`fc63e1b4dbfacf7e2449bf0706226f9f6eea583e` (image tag `ubuntu24/20260907.300`):
`images/ubuntu/scripts/build/configure-system.sh` recursively sets mode0777 on
`/opt` and adds sticky bits to Ruby directories after archive installation, as
ordered by `images/ubuntu/templates/build.ubuntu-24_04.pkr.hcl`. This does not
attest the whole hosted image or permit relocating the fixed-prefix Ruby archive.

Synthetic installer fixtures and workflow mutation checks are separate from
actual provider-archive/host execution. A source review or an exact version string
is not proof of a successful Apple preflight, signed build or Store upload.
Desktop's separately selected JDK 17, Android SDK components and whole hosted
OS/Xcode image bytes are not covered by these named archive pins. Preserve these
limits when reporting App61 results.
