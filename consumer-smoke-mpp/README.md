# Multiplatform Consumer Smoke

This standalone Gradle build validates the published Lets-Plot Compose artifacts from an external consumer boundary.

It deliberately has no project dependency on the repository root. CI supplies three Maven repositories through:

- `LETS_PLOT_CORE_REPO`
- `LETS_PLOT_KOTLIN_REPO`
- `LETS_PLOT_COMPOSE_REPO`

The smoke build uses one common `PlotPanel` source and verifies:

- Android release compilation and AAR assembly.
- WasmJS production browser bundling.

This catches missing target publications, broken Gradle module metadata, variant-selection regressions, and target-specific transitive dependency gaps that an in-repository source build can miss.

## Android toolchain

Use this build's own wrapper (`./gradlew` from this directory, or
`./consumer-smoke-mpp/gradlew -p consumer-smoke-mpp` from the repository root).
It pins Gradle 9.3.1 for AGP 9.1.1 and compile SDK 37, as required by the
Compose 1.12.1 Android AAR metadata. JDK 17 or newer is required.
The repository-root wrapper is for the separate producer build.

The legacy KMP Android target temporarily uses `android.builtInKotlin=false`
and `android.newDsl=false` to retain its instrumented-test variant on AGP 9.
The emulator remains API 34; compile SDK and device API level are independent.

CI builds `assembleDebugAndroidTest` before booting the emulator to catch
dependency metadata, compilation, and APK packaging failures early. The
emulator then runs `connectedDebugAndroidTest` against the published artifacts.
