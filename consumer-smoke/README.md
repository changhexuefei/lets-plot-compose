# Windows/JDK 21 Consumer Smoke Test

This is a deliberately standalone Compose Desktop consumer. It must resolve Lets-Plot artifacts from Maven repositories supplied through environment variables and never uses Gradle project dependencies from the source repositories.

The smoke test validates the published compatibility chain:

- Lets-Plot Core `4.11.1-SNAPSHOT`
- Lets-Plot Kotlin API `4.15.1-SNAPSHOT`
- Lets-Plot Compose `3.2.3-SNAPSHOT`
- Kotlin `2.4.20`
- Compose Multiplatform `1.12.1`
- Windows + JDK 21 runtime

Runtime checks cover first render, window resize, tooltip hover, Ctrl+Shift wheel zoom, Ctrl+Shift drag pan, synthetic 1.0x/1.25x/1.5x density changes, and closing/reopening the Compose window. Screenshots and a summary file are produced under `build/smoke`.


## Graphite / Skiko upgrade baseline

The Windows smoke is also the canonical renderer-upgrade evidence producer.

- Canonical baseline ID: `windows-jdk21-software`
- Renderer selection is explicit through `SMOKE_RENDER_API`; the stable default remains `SOFTWARE`.
- Each CI run keeps the nine existing checkpoint screenshots and `smoke-summary.txt`.
- `renderer-dependencies.txt` records the resolved Skiko runtime dependency chain.
- `baseline-manifest.txt` records the baseline ID, requested renderer, workflow identity, required screenshot count, and SHA-256 for every PNG.
- The long-lived artifact is named `graphite-skiko-baseline-<baseline-id>-<commit-sha>` and is retained for 90 days.

Comparison rules are defined in [BASELINE.md](BASELINE.md). Renderer-specific experiments must use a distinct baseline ID rather than replacing the stable software baseline.
