# Phone UI v343 audit

Date: 2026-09-08. Base: 675730c7842356cae938b17549ff3efd2f49274c (v342 beta).

## Scope and release boundary

Review the complete manual phone-agent observation/action loop, not just two error
messages. Do not expose phone-agent tools to Codex, Claude or Minis. Do not modify
the communication application. Keep manual gesture, clipboard, input-method
dismissal, overlay and accessibility behavior byte-identical to v342. No device
UI automation or inspection of another application's private data was used.

## Primary references

- [Open-AutoGLM agent loop](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/agent.py)
- [Open-AutoGLM client and defaults](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/model/client.py)
- [Open-AutoGLM Chinese prompt](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/config/prompts_zh.py)
- [Hosted AutoGLM-Phone specifications](https://docs.bigmodel.cn/cn/guide/models/vlm/autoglm-phone)
- [Operit phone agent](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/core/tools/agent/PhoneAgent.kt)
- [Android frame callback contract](https://developer.android.com/reference/android/media/MediaCodec.OnFrameRenderedListener)
- [Android PixelCopy contract](https://developer.android.com/reference/android/view/PixelCopy)
- [Android encoder format keys](https://developer.android.com/reference/android/media/MediaFormat)

## Confirmed implementation defects and changes

1. v342 waited for an informational frame-rendered callback before copying a
   frame. Android explicitly permits delayed/batched callbacks. Use decoded
   output queued to the Surface, drain output both during input and capture, and
   require PixelCopy success. Do not fall back to a cached PNG or a synthetic
   black screen. A second v342 error required a frame newer than every capture
   request. Android's repeat-previous-frame key guarantees only one repetition,
   not a continuous heartbeat. Static pages may correctly stop producing frames.
   Instead drain the input received before capture and copy the latest real
   Surface buffer; do not mistake an unchanged screen for a broken stream.
2. PixelCopy destination was recycled on coroutine cancellation while native
   copying could still be in flight. Its completion callback now owns disposal.
3. Decoder input starvation silently dropped a compressed reference frame.
   Drain before retrying input; if still stalled, reset and wait for an IDR
   rather than continuing an invalid reference chain. Reattached renderers
   request codec configuration and a sync frame from the existing display.
   This does not create a second display or replace the screenshot source.
4. Every historical user entry repeated the full original task; assistant text
   was sliced at 12000 characters, potentially cutting off its action. Native
   history now preserves the initial task pair and recent complete pairs, uses
   canonical think/answer entries, bounds reasoning without cutting the action,
   and does not repeat the task in every Screen Info message. Only the current
   request carries an image, placed before text as in the official client.
5. Truncated responses were treated as ordinary parse errors, then a prefix of
   the malformed assistant output was added to retries. Reject length-truncated
   actions, rebuild retries from the original observation with a correction,
   and never insert malformed assistant content. Attempts are bounded. Hosted
   BigModel documentation specifies 2048 maximum output tokens, whereas the
   self-hosted example defaults to 3000. Keep the official client setting and do
   not try to solve repetition by doubling output beyond a provider limit.
6. Unsupported native actions and pixel-space coordinates could reach the
   executor rather than the correction loop. Validate them before execution.
   Do not execute an example inside a closed thinking block as the final action.
7. Future.cancel reports a done future before blocking HTTP has returned. A new
   task could reset shared cancellation state while the old worker remained
   alive. Interrupt the worker without prematurely completing its future; keep
   ownership until the actual worker exits. Check cancellation after capture
   and before request retries. Existing HTTP connect/read timeouts still apply.
8. A paused/discarded observation consumed the action step budget. Increment
   the budget only after accepting a model decision. Reset comparison state on
   resume and discard decisions made against the pre-pause observation.
9. Sparse pixel sampling could imply failure despite small text/cursor changes.
   Feedback now explicitly states this measurement cannot establish failure.
10. Short finish messages were prefixed with an invented task-success claim.
    Preserve the actual finish text instead of converting a failure or a request
    for clarification into a success narrative.

## Official parity and deliberate differences

The app is not a byte-for-byte reproduction of the official Python framework.
It uses Shizuku/Binder instead of ADB, existing clipboard entry instead of
ADBKeyboard, and a virtual-display H.264 Surface instead of physical-display
screencap. These adaptations remain necessary and must not be silently replaced.

Sampling remains temperature 0, top_p 0.85 and frequency_penalty 0.2 for the
native preset. The official client streams; this client still collects a complete
HTTP response before parsing. Streaming is not evidence that a partial action is
executable, and a transport rewrite is not included without its own tests.

The official loop and Operit both retain canonical assistant history and remove
old images. Their subsequent user messages describe the current screen instead
of repeatedly restating the full original task. This audit aligns those aspects.
History here remains bounded for a hosted 20K context. Foreground package names
are not guessed from main-display state when operating a virtual display.

The custom prompt names only supported gestures and preserves keyboard-dismissal
instructions. It is not replaced wholesale with upstream prompt rules that can
inject unrelated task actions or automatic sensitive-screen assumptions. No new
host click-repeat blocker, login/CAPTCHA classification or spoken live-region
announcement is introduced. Actual device/API screenshot failures must remain
errors, not fabricated visual observations.

## Verification requirements and limits

- Preserve executeAction SHA-256:
  98dae61aa93f4e2a3b734da985facfa992599515c120d963046de8bd6a157f1c.
- Preserve PhoneUiAgentActivity, progress overlay and layout hashes in the
  source contract suite; keep removed phone-agent MCP routes absent.
- Run parser/history/retry unit cases including long reasoning, malformed
  thinking, unsupported actions, wrong coordinate units and repeated retries.
- Run all source contracts, unrelated history/collaboration tests and beta
  compilation before packaging is accepted.
- Check pause during request/capture, cancellation before worker entry and
  cancellation during network wait against actual ownership rules.
- Check stream attach/reconnect, codec configuration, IDR acquisition, static
  frames, decoder output draining and PixelCopy cancellation by source review.

Source inspection proves these mechanisms exist; it does not prove that each
reported Taobao/JD failure was caused by every mechanism above. No live shopping
task, paid model benchmark, physical MediaCodec test or screen-reader gesture
test was run in another app. Do not claim a 98% success rate or that model
hallucination, vendor codec behavior and provider outages have been eliminated.
The user must validate real beta-device behavior; retain the previous APK and
source commit for rollback.

Local verification: all 28 Node source/behavior tests passed on 2026-09-08,
including unchanged action/UI hash checks and Codex/collaboration regressions.
The first run had two missing-source errors after prior workspace cleanup;
repeating with the exact pinned OpenMinis source resolved both. Android unit
tests and beta compilation are enforced by the packaging workflow, not claimed
as locally executed.
