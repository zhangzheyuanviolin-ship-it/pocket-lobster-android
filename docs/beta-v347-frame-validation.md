# Beta v347: usable frame validation

This beta fixes the phone UI agent observation boundary without changing the established input, keyboard, tap, swipe, or conversation controls.

- Existing target tasks on the virtual display are reused instead of launching duplicate welcome tasks.
- Structurally valid but content-free black video frames are rejected before a model request.
- Accepted and rejected frame quality measurements are retained in the private task diagnostics.
- AutoGLM uses the official 65,536-position context window instead of the previous 20,000-token assumption.
- AutoGLM, GUI Plus, and generic JSON prompts explicitly reject sensitive-screen inference from black or stale observations.
- GUI Plus terminate actions are represented as failures, while answer, done, and completed remain successful terminal actions.
- English application names with leading punctuation, including Taobao, resolve through installed-app aliases.
