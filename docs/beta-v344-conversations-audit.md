# Beta v344: provider routes and phone conversations

Base: `3b51c0b7fb735283b20fc9108f0770392230fa99` (v343).
Target: `com.codex.mobile.pocketlobster.beta`, versionCode 344. No automatic installation.

## Codex

The standalone send exception handler incorrectly opened the collaboration board.
It now captures the submission mode before awaiting and only renders collaboration
errors for collaboration submissions. Standalone errors remain in the standalone
chat and the input is preserved by the existing completion callback.

The current app-server protocol returns the live provider in the top-level
`ThreadResumeResponse.modelProvider`. The nested thread metadata can still
describe its original provider. Routing now checks the live field first, persists
the verified selection for subsequent history opens, and never accepts a genuine
live-provider mismatch. Thread IDs, visible messages and workspace are retained.
Provider-bound reasoning/compaction migration is retained. Process shutdown is
awaited before editing persisted history, and SIGKILL fallback checks actual exit
state rather than the `killed` flag (which only means a signal was sent).

Reference: [OpenAI app-server documentation](https://developers.openai.com/codex/app-server)
and the `rust-v0.153.4` thread processor/lifecycle source in openai/codex.

## Phone conversations

New task creation and continuation are distinct. Sending in an existing phone
conversation queues a new instruction; a single worker consumes it after old I/O
exits. Revision checks discard old observations. The old HTTP request is disconnected
without interrupting the clipboard/keyboard action midway. New rounds reset the
executed-step counter and use the selected maximum; the plain Resume button does
not reset the counter. Completed, failed, cancelled and step-limited conversations
can continue. New Conversation explicitly starts with empty context.

Text model history, the original instruction, the latest instruction, last action
result and compressed memory persist in the existing task state. Prior rounds
remain separately selectable. The current task is merged into the history list
immediately, without waiting for the next task to archive it; the open history
dialog updates its rows while visible. Loading history restores its conversation
instead of starting an unrelated task. App restart does not silently resume device
actions: the user sends a continuation instruction explicitly.

An existing virtual display is not prewarmed/relaunched on continuation. A newly
created display still receives the original bootstrap/target initialization so
restart recovery cannot leave it without a visible window.

## Context budget

Known windows checked on 2026-09-09:

- AutoGLM Phone: 20,000, [Zhipu documentation](https://docs.bigmodel.cn/cn/guide/models/vlm/autoglm-phone).
- GUI Plus and 2026-02-26: 256,000, [Alibaba documentation](https://help.aliyun.com/zh/model-studio/gui-plus).
- Qwen 3.5 Plus/Flash, 3.6 Flash, 3.7 Plus, 3.8 Max/Flash: 1,000,000, the corresponding Alibaba model pages (for example [3.8 Flash](https://help.aliyun.com/zh/model-studio/qwen3-8-flash), [3.6 Flash](https://help.aliyun.com/zh/model-studio/qwen3-6-flash), [3.5 Flash](https://help.aliyun.com/zh/model-studio/qwen3-5-flash)).

Unknown IDs use a conservative 16,000 working assumption, not an advertised limit.
Text token use is estimated, not tokenizer-exact. The policy reserves capacity for
the screenshot, system instruction and output, and compacts before the preexisting
24-message window would discard history. It also caps the working text budget at
12,000 tokens to limit mobile latency and cost. There is no claim that quality
universally degrades at a particular percentage.

The configured generic phone model supplies summaries when available; otherwise
the current phone model is used. No other agent is started and no hidden credentials
are borrowed. Summaries retain goals, constraints, confirmed outcomes, uncertainty,
and unfinished work. Recent turns remain verbatim. Failed, empty or truncated
summaries do not erase the previous context; the error remains visible and a user
can retry or change the configured model. Remote summarization quality remains
model-dependent and requires real-device evaluation. Summarization is a separate
bounded text request, never an executable phone action.

## Protected behavior and verification

SHA-256 contracts preserve the v343 action executor, screenshot helpers, all three
system prompts, action parsers and progress overlay. Shower renderer/server and
keyboard dismissal implementations are unchanged. No delegated phone MCP tools,
sensitive-screen inference, repeated-coordinate interception or live accessibility
announcements were introduced.

Local checks: Vue type check; 31 Node tests; isolated real-CLI seven-turn test using
only a localhost simulated OpenAI/provider API. The latter exercises OpenAI and
two custom routes without spending real API quota. It waits for real turn-completed
notifications rather than treating an early persisted snapshot as completion.
Additional JVM tests cover context reload, original/latest instruction preservation,
compaction before truncation, and failed compaction transactions. Android compilation
and JVM tests are required in the beta build workflow before publishing an APK.

No Android UI automation was used. No private data of the separately installed beta
app was inspected. These tests do not substitute for TalkBack/Baoyi and live model
acceptance testing on the installed beta APK.
