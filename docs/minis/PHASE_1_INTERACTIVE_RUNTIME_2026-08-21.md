# OpenMinis Interactive Runtime

Date: 2026-08-21

## Testable scope

The beta application replaces every visible OpenClaw agent entry with the official OpenMinis 1.12 Android activity. The agent hub opens Minis home, the host model manager opens the official provider list, and the new-chat affordance creates an official Minis chat session.

The test flow is provider creation, API credential entry, model selection, new chat, user-message rendering, provider response rendering, and conversation persistence inside the official Minis database and UI.

## Runtime packaging

CI recursively checks out the pinned OpenMinis submodule, builds the upstream PRoot loaders, prepares the Alpine minirootfs, and packages the official native bridge libraries. Pocket Lobster's existing Ubuntu PRoot binary is renamed to libubuntu_proot.so so the Ubuntu and Minis Alpine runtimes cannot overwrite one another.

The legacy OpenClaw runtime payload is no longer bundled. Complete rollback remains available through the immutable golden-beta-v299-20260820 tag and archived v299 beta APK.

## Integration boundaries

OpenMinis runs under com.openminis.app.MinisApp inside the Pocket Lobster beta package and owns its official Compose UI, Room database, provider repository, chat runtime, visible browser, and Alpine terminal. Codex and Claude Code retain their existing host activities and storage.

The upstream standalone update section is disabled because updates for the embedded runtime must be distributed as signed Pocket Lobster beta APKs. The upstream source remains pinned to tag 1.12 and commit 09fc199928de0f26685e766c34e6d541c7a69e5a.
