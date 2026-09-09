# Beta v345: phone context and capture lifecycle

Base: 8d12fc607f2419d1f4107217022007dc5913193b (v344).

## Findings

The v344 history-count trigger forced summarization after 12 decisions regardless
of the model window. Its 12000-token text ceiling also made GUI Plus behave like
a small-window model. The request builder independently discarded history past
24 messages. These are confirmed code defects, not provider failures. Removing
only the compaction trigger would have silently discarded unsummarized context.

The supplied trace includes repeated actions and blank-page descriptions BEFORE
the first summary. Compaction therefore cannot explain every failure. A model's
statement that an image is black does not itself prove decoder corruption.

Each continuation re-ran display setup and unconditionally removed the capture
Surface/decoder, even when the same virtual screen was still alive. Reattaching
to a static display requires codec configuration and a new key frame without
the app launch that primed the initial round. Display dimensions were also
recomputed from host window metrics; a changed host size could destroy the old
virtual display while a boolean still incorrectly selected the reuse branch.

Additional capture defects: decoder resets did not invalidate pending PixelCopy
results, PNG compression ran on the Android main callback thread, repeated sink
registration accumulated death recipients, and a missing server display could
silently accept sink attachment. Each is corrected in the owning layer.

## Changes

- Retain complete conversation messages until a model-specific capacity trigger.
  Reserve 6000 tokens for system instructions, current image and output, then
  compact at 85% of the configured context window. This is a conservative text
  estimate, not a measured quality threshold. Known defaults: AutoGLM 20000,
  GUI Plus 256000. A model editor override supports custom limits.
- Remove the step/message-count trigger and hidden 12000-token ceiling.
- Preserve both recent user/assistant pairs when committing a summary, with a
  prepended memory anchor; no old user text is discarded and no synthetic
  assistant response is inserted into the native action protocol.
- No automatic choice of a billable summary model. The model manager allows
  selection of configured phone, Codex, Claude and Minis API-key models or a
  separately encrypted custom endpoint. Support Chat Completions, Responses,
  Anthropic Messages and Gemini. OAuth-only and Azure-specific configurations
  are not silently repurposed as ordinary API keys.
- Without a selected/available summary model, pause near capacity with context
  preserved. Failed summaries pause for user retry instead of erasing history
  or repeatedly spending money automatically.
- Reuse an attached decoder for the same live display and fixed video size.
  Explicit recovery can rebuild it. Compare actual display identities after
  setup before preserving the current app. Never send an old-generation copy.
- Compress successfully copied bitmaps off the main thread and retain correct
  bitmap ownership across cancellation. Export frame generation, dimensions,
  queued/submitted timestamps and PixelCopy result for future diagnosis.
- The 500ms UI poll excludes model-only conversation history. It still exposes
  user-visible rounds/events and does not announce changes automatically.

## Boundaries and validation

Codex routing, provider switching and collaboration code are unchanged.
Phone executeAction, action parser, all three action system prompts and progress
overlay retain their protected hashes. Clipboard, IME dismissal, coordinates,
swipe timing, main-screen capture and request retry policies are preserved.
No repeat-click blocker, synthetic screenshot or sensitive-screen classification
was added. The phone agent remains manual-only.

Local Node regression suite: 31 tests passed. Android JVM tests cover real
loopback HTTP requests for all four summary protocols, Unicode payloads,
truncation, invalid summaries, long GUI histories and memory retention. The beta
workflow runs JVM tests before accepting its APK and verifies package, version,
runtime payload, signatures and process isolation.

No UI automation of another installed Android app, paid model benchmark or
physical MediaCodec instrumented test was performed. Device-specific freezes,
model hallucination and the actual Taobao/JD success rate remain user-validation
items; do not claim a measured improvement or a guaranteed success rate.

Primary references checked 2026-09-09:
- https://docs.bigmodel.cn/cn/guide/models/vlm/autoglm-phone
- https://help.aliyun.com/zh/model-studio/gui-plus
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/view/PixelCopy

Release scope: only com.codex.mobile.pocketlobster.beta. Preserve v344 and v343
APKs and source commits for rollback. Do not install over the communication app.
