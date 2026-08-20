# OpenMinis Phase 1 Foundation

Date: 2026-08-20

## Scope

This phase establishes a reproducible and reversible OpenMinis integration baseline. It does not enable the Minis runtime and does not alter Codex, Claude Code, OpenClaw, provider switching, browser automation, or terminal behavior.

## Upstream lock

- Repository: `https://github.com/OpenMinis/OpenMinis`
- Tag: `1.12`
- Commit: `09fc199928de0f26685e766c34e6d541c7a69e5a`
- License: GNU GPL v3
- Source location: `third_party/OpenMinis`

The Git submodule commit is authoritative. The `branch = main` setting is only an update hint; builds must match the locked commit exactly.

## Golden rollback

- Git tag: `golden-beta-v299-20260820`
- Commit: `9bbaba40efe308373090ad3552d6126f9c568075`
- Beta package: `com.codex.mobile.pocketlobster.beta`
- Version: `1.0.58-codex-cli-0.147.0-gpt-5.6-responses-v299-beta`
- APK SHA-256: `d57589bd40555736a2234b5b0f70832e0870df7583c16a3a094155fb6ba2b5c4`

No later Minis phase may rewrite this tag or overwrite the archived APK.

## Distribution

Pocket Lobster is distributed under GNU GPL v3 from this integration line. Historical MIT attribution is retained under `LICENSES/`. OpenMinis GPL and third-party notices are present both in the source tree and APK assets.

## Build baseline

The beta build remains behaviorally identical to v299 except for version and compliance metadata. CI checks out the OpenMinis submodule, verifies its commit, tag, license hashes, Android build declarations, and golden rollback metadata, then prepares JDK 17, Android API 36, NDK r28, and CMake 3.22.1 for later native integration phases.

## Next phase boundary

The next phase may introduce the shared visible browser capability behind a beta-only feature flag. OpenClaw removal is explicitly prohibited until the Minis replacement passes the complete regression matrix.
