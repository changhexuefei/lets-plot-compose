# Windows/JDK 21 Consumer Smoke Test

This is a deliberately standalone Compose Desktop consumer. It must resolve Lets-Plot artifacts from Maven repositories supplied through environment variables and never uses Gradle project dependencies from the source repositories.

The smoke test validates the published compatibility chain:

- Lets-Plot Core `4.11.1-SNAPSHOT`
- Lets-Plot Kotlin API `4.15.1-SNAPSHOT`
- Lets-Plot Compose `3.2.3-SNAPSHOT`
- Kotlin `2.4.20`
- Compose Multiplatform `1.12.1`
- Windows + JDK 21 runtime

Runtime checks cover first render, window resize, tooltip hover, Ctrl+Shift wheel zoom, Ctrl+Shift drag pan, synthetic 1.0x/1.5x/2.0x density changes, and closing/reopening the Compose window. Screenshots and a summary file are produced under `build/smoke`.
