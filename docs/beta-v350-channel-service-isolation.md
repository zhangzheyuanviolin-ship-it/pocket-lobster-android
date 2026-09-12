# Beta v350: channel service isolation

This beta isolates every app-private loopback service by installed channel. Production, operator, beta, and standalone OpenMinis installations now receive distinct ports for the app server, collaboration API, CONNECT proxy, Shizuku host bridge, Minis runtime bridge, OpenClaw gateway, and OpenClaw control UI.

The change fixes two independent collaboration failures: native Claude and Minis coordinators no longer wait on another installed app's server, and the Codex coordinator no longer sends Minis member tasks to another app's authenticated runtime bridge. Existing persisted OpenClaw plugin URLs are refreshed to the active channel on startup.

The phone UI golden baseline remains frozen at `phone-ui-golden-v348`; its protected Android, capture, prompt, model, interaction, and test files are byte-identical and enforced by the build gate.
