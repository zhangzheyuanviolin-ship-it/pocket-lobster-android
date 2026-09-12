# Beta v351: collaboration capability parity

This beta fixes a role-dependent Claude Code launch path in three-agent collaboration.
Claude coordinators already received the AnyClaw toolbox through the full collaboration
MCP profile, while Claude workers were launched without an MCP profile and therefore
only saw the Claude CLI built-in tools.

The host now installs and verifies two Claude collaboration profiles. Coordinators get
the unchanged AnyClaw toolbox plus the four durable collaboration tools. Workers get
the same AnyClaw toolbox, bridge endpoints, shared-storage access, runtime commands,
and credentials as coordinators, without coordinator-only delegation tools. Every
collaboration run checks worker readiness before it starts.

The phone UI golden surface remains frozen at `phone-ui-golden-v348`; its source and
Shower payload hashes are enforced by the existing build gate and are unchanged here.
