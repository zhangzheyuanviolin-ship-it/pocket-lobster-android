# v345 device evidence review (2026-09-10 UTC)

## Scope and release decision

Read-only inspection of the installed beta's task state, task history, Shower server log,
and Android display/activity metadata. No device UI was driven, no models were called,
no runtime was changed, and no replacement APK was built. This is an incomplete incident
investigation, not a claim that the regression is fixed.

The two reports require separate observations: GUI Plus repeatedly waits/presses Home;
AutoGLM repeats a login request after explicit second/third-round user instructions.
Neither observation alone proves a model defect, a compaction defect, or a screenshot defect.

## Confirmed evidence

Installed beta state for task `502fe242-2a45-4958-bbea-658afc3e1301`:

- Original task: open Taobao and collect coins; display 3, 1088 x 2256.
- The first Tap executed at 00:34:31.179711, physical coordinates 976,2187.
- First login takeover: 00:34:35.629432.
- Round 2 instruction received at 00:35:30.442507: the user has logged in.
- Round 3 instruction received at 00:36:21.263648: login is complete, continue.
- Persisted model history contains both new instructions in their respective user messages.
  The repeated login output is not explained by the host dropping those instructions.
- Round 3 observation at 00:36:22.884272 reports display 3, generation 2,
  queuedUs=submittedUs=7232338712, PixelCopy result 0, SHA-256
  `8b5d7626ff9ef9c2e0252a7fc2fdc1f3392af6458647e131093b64273b0f4ebb`.
- Context memory is empty. No compaction occurred in this conversation. Compaction cannot
  account for this specific repeated-login incident.
- The server log confirms creation of display 3 and one Taobao launch at 00:34:24.
  No subsequent launch is recorded during the two continuation rounds.
- At 00:37:06 the client binder died; the server exited at 00:37:07.
  This happened after the login loop, not before its first occurrence.

GUI task `3b8089f8-6932-4a30-a210-4d3292deb107` has a final observation at
00:32:36.000861, display 2, generation 2, queuedUs=submittedUs=7081369500,
PixelCopy result 0, SHA-256
`9a24ae5fee4a2266c0fe64f19e70695a3b4bb98ecbe1df76ff08eb576b61dc80`.

The final observations contain hashes and decoder timestamps, not original image bytes,
server-side encoded-frame sequence numbers, or contemporaneous task/window routing.
No phone-agent PNG was found in its task storage. Browser screenshot files are unrelated
and were not read or deleted. Historical task data was filtered inside the beta process
environment to avoid the shell bridge's output truncation limit.

## Second review: what the code actually guarantees

`ShowerVideoRenderer.captureCurrentFramePng` snapshots the latest *already queued* PTS,
drains until submitted PTS reaches it, then PixelCopies the Surface. A stopped producer
with an old decodable buffer satisfies this condition indefinitely. PixelCopy success
does not establish producer liveness, current window identity, or that the newly submitted
decoder output has already become the copied Surface buffer.

Conversely, an unchanged screen can legitimately stop producing new frames. A simple
frame-age timeout would misclassify a healthy static page; it is not an adequate fix.
Rebinding a decoder does not prove that the application is still on its virtual display.

Continuation intentionally skips relaunch if the display ID is unchanged. It does not
check whether the target activity has moved to another display during manual takeover.
An unconditional relaunch would also be incorrect: it could discard the user's current
in-app navigation. A display ID alone is not a sufficient continuation invariant.

The encoded stream uses synchronous Binder delivery. Server delivery exceptions clear
the sink without recording the exception. This hides whether a transport failure occurred;
no retained evidence proves such a failure happened in these specific tasks.

Comparison with stable v343: the screenshot/input/action implementations largely predate
multi-turn support; v345 changed capture lifetime, PixelCopy ownership/compression, decoder
generation invalidation, and continuation reuse. It is not justified to attribute all
symptoms to the summary model or to rewrite the gesture/IME code.

## Evidence required before a root-cause repair is declared

Collect one synchronized observation at the failure boundary: the exact PNG supplied to
the model, its decoder generation/PTS, server last encoded/delivered frame identity and
delivery error, plus actual foreground task/window display IDs. Compare the same fields
before and after manual takeover. Do not infer screen identity from model descriptions,
PNG hashes alone, or post-restart dumpsys output.

A repair must distinguish: live unchanged page, dead video sink, decoder output not yet
latched, activity moved to main display, and a genuinely current image misunderstood by
the model. Regressions must cover a static page, a changing page, takeover on either
display, second/third-round continuation, and surface recreation. No sensitive-screen
classification or repeated-coordinate blocking should be introduced.

References reviewed: upstream Operit ShowerVideoRenderer source, Android MediaCodec and
PixelCopy API contracts, Android TransactionTooLargeException documentation. Those
references describe mechanisms; they are not proof of this device's incident cause.

- https://github.com/AAswordman/Operit/blob/main/showerclient/src/main/java/com/ai/assistance/showerclient/ShowerVideoRenderer.kt
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/view/PixelCopy
- https://developer.android.com/reference/android/os/TransactionTooLargeException
