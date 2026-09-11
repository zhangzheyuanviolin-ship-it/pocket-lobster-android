# Beta v348: synchronized phone observations

This beta fixes a systemic observation lag in the phone UI agent without changing the established input, gesture, conversation, or model-action parsers.

## Device evidence

The v347 evidence journal showed the virtual display task already on a Taobao detail activity while the model image still showed the previous search-result page. Client image timestamps also trailed the Shower encoder state by roughly three seconds. The decoder converted every 1088x2256 YUV frame into a bitmap on the Binder delivery path, allowing old frames to accumulate and making a visually correct coordinate act on a different live page.

## Changes

- YUV-to-bitmap conversion is now demand-driven and runs only for a requested model observation.
- Every post-action observation must be newer than the frame used to choose that action.
- Sparse decoder startup frames are rejected before any model request.
- The foreground activity for the selected virtual display accompanies the current screenshot.
- Multimodal requests use the Open-AutoGLM-compatible text-then-image order.
- Prompt text no longer introduces speculative screen categories that can bias a model when an image has little content.
- Action-specific settling remains bounded and does not block or rewrite repeated model actions.

## Invariants

Codex routing, independent-agent sessions, collaboration mode, model configuration, context continuation and compression, text input handling, normalized coordinates, and accessibility behavior are unchanged.
