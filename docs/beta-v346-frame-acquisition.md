# Beta v346: frame acquisition and continuation

## Verified version lineage

Read-only package inspection on 2026-09-10 confirmed communication package
`com.codex.mobile.pocketlobster.test` is versionCode 341 / 1.0.100, while
`com.codex.mobile.pocketlobster.beta` is versionCode 345 / 1.0.104.

- v341, `8badc06`: validated baseline merged to main. Simple preview-Surface PixelCopy;
  no persistent multi-round phone conversations.
- v342, `675730c`: Codex history routing/loading fixes and new frame freshness waiting.
  This introduced the requirement for a newly rendered frame after each capture request,
  although static screens need not produce one. It also relied on delayed frame-rendered
  callbacks. This is the first identifiable freshness/rebind regression in this lineage.
- v343, `3b51c0b`: removed the static-page requirement; improved model truncation retries,
  canonical native action history, NAL parsing, decoder drain and key-frame handling.
  User reported improvement with all three phone protocols.
- v344, `8d12fc6`: fixed Codex provider switching and added persistent phone conversations,
  per-round budgets and compaction. The fixed short history trigger caused excessive
  compaction. Display/surface reinitialization and cross-display takeover were not fully
  reconciled with multi-round continuation.
- v345, `afdd5d2`, `2d55b3b`, `3a75c8f`: replaced early compaction with estimated capacity
  budgeting, added explicit summary-model selection, made Minis discovery read-only,
  preserved native history structure, and reused capture surfaces. It still treated
  successfully copying a decoder Surface as sufficient evidence of a current observation,
  and skipped continuation relaunch based only on unchanged display ID.

Not every reported task failure can be assigned to a single commit: v345 incident records
do not include original frames/window topology. See the separate device-evidence review.
In particular the repeated-login conversation contained no summary, so summary quality is
not a valid explanation for that incident.

## Changes

The model's native H.264 decoder now uses CPU-readable MediaCodec output images, not a tiny
overlay SurfaceView. Capture waits for the actual acquired image timestamp corresponding
to queued decoder input. Preview visibility, SurfaceView creation/destruction, PixelCopy
timing and display scaling cannot substitute another preview buffer for that image.
Image planes honor crop, row stride and pixel stride; images are copied and closed before
the decoder output buffer is released. Only the latest bitmap is retained.
The visible takeover preview still uses its existing renderer.

The server retains only a bounded complete latest GOP for decoder reattachment. An IDR
request alone cannot revive a newly attached decoder on a static source. Video packets
larger than 64 KiB use ordered bounded Binder chunks; an individual large frame no longer
requires a megabyte-scale Binder transaction. Transient delivery failures are logged and
do not silently discard a live sink. A pre-capture RPC checks producer/sink state and
repairs delivery before a model request.

The same RPC reads structured Android root-task metadata. If the known target task has
moved from the virtual display to display 0, its existing root task is moved back, preserving
its activity stack. A target already on the virtual display is not relaunched. Unknown
metadata does not trigger a speculative relaunch or imply that login is missing.

The exact model-input PNG and matching observation metadata/model output are retained in
the beta's own private `files/phone-ui-agent/observations` directory: at most eight images,
24 MiB of image data, plus bounded JSON sidecars. There is no credential logging or upload.
This permits read-only retrieval with the beta's own run-as identity; it does not assume
the communication app can directly read another package's private storage.

## Invariants and verification

The gesture/input/IME implementation, all three model prompts/parsers, context budget and
summary selection, Codex routing, and screen-reader announcement policy are unchanged.
Source tests retain byte-level hashes for these protected paths.

`ShowerCaptureSelfTest` is a headless device test of the production capture component. It
generates synthetic red/blue H.264 frames, verifies decoded pixels and the newest output,
recaptures a static source, then recreates the decoder and replays the stream. It creates
no Android display/window and does not launch, tap or inspect any third-party app. Run it
against the built APK before accepting the artifact. Cloud build also runs source contracts
and the app's existing JVM tests. Synthetic codec tests do not constitute a measured shopping
task success rate; only real subsequent usage can establish that rate.

The initial candidate used an ImageReader surface. The device test on 2026-09-10 rejected
that candidate: Qualcomm decoder output triggered nativeCreatePlanes JNI abort when reading
the planes. It is not being delivered. The revised path requests flexible YUV byte-buffer
decoding and uses MediaCodec.getOutputImage directly; the preview path remains unchanged.

References: Android MediaCodec supports getOutputImage in byte-buffer mode. Android Media3
also documents that some hardware decoders cannot write CPU-readable ImageReader frames.

- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/ImageReader
- https://developer.android.com/reference/androidx/media3/test/utils/VideoDecodingWrapper
- https://developer.android.com/reference/android/os/TransactionTooLargeException
