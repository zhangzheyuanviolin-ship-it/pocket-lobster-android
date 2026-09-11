# Beta v349: shared runtime channel isolation

This beta isolates the embedded OpenMinis browser and Alpine bridge by Android application channel. Production, operator, beta, and standalone OpenMinis processes no longer compete for the same localhost port while using different private bridge tokens.

The runtime CLI first honors `ANYCLAW_MINIS_BRIDGE_URL`, then reads the app-private `shared-runtime/bridge-port` file, with the production port retained only as a compatibility fallback. Codex and Claude configurations receive the channel-specific endpoint explicitly.

The device-verified phone UI agent implementation from tag `phone-ui-golden-v348` is frozen by `tests/phone-ui-golden-freeze.test.mjs`. The guard hashes its runtime, protocol, capture, virtual display, input, UI, and related test files and fails the build if any protected byte changes.
