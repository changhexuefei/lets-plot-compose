# Lets-Plot Compose Frontend

[![Experimental](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html)
[![JetBrains incubator project](https://jb.gg/badges/incubator.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![License MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://raw.githubusercontent.com/JetBrains/lets-plot-compose/master/LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/JetBrains/lets-plot-compose)](https://github.com/JetBrains/lets-plot-compose/releases/latest)

**Lets-Plot Compose Frontend** is a Kotlin Multiplatform library that allows you to embed \
[Lets-Plot](https://github.com/JetBrains/lets-plot) charts in a [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) \
application targeting Desktop, Android, and WasmJS.

### Supported Targets

- **Desktop** (macOS, Windows, Linux)
- **Android**
- **WasmJS**

For more details see [Compose multiplatform compatibility and versioning overview](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html).

### Current Development Baseline

The current `main` branch is the **3.2.3-SNAPSHOT** development line and is validated with:

- Kotlin **2.4.20**
- Compose Multiplatform **1.12.1**
- Lets-Plot Kotlin API **4.15.1-SNAPSHOT**
- Lets-Plot Multiplatform **4.11.1-SNAPSHOT**
- Android compile SDK **35**, minimum SDK **24**

The latest published dependency examples below intentionally remain on the latest released artifact line until 3.2.3 is released.

The current cross-platform regression baseline also verifies the following behavior on Desktop, Android, and WasmJS:

| Capability | Desktop | Android | WasmJS |
| --- | --- | --- | --- |
| Compose Canvas plot rendering | Yes | Yes | Yes |
| Default Pan / Rubber Band Zoom / Centerpoint Zoom / Reset toolbar | Yes | Yes | Yes |
| Shared pointer drag / move / wheel interaction contract | Yes | Yes | Yes |
| Ctrl / Alt / Shift / Meta interaction modifiers | Yes | Yes | Yes |
| Computation-message redispatch after plot-spec replacement | Yes | Yes | Yes |
| Hyperlink navigation | Yes | Yes | Yes |

The toolbar implementation and interaction contract are shared from common code where practical, while platform adapters retain only the platform-specific event and navigation integration. The compatibility CI compiles all three targets and emits dedicated regression evidence for these contracts.

### Release-Candidate Evidence

The 3.2.3 stabilization line now validates the **published Maven boundary**, not only in-repository target compilation:

- Desktop: standalone Windows/JDK 21 consumer plus the 9-screenshot regression baseline.
- Android: standalone Kotlin Multiplatform consumer resolving the published Android variant and assembling a release AAR.
- WasmJS: the same standalone consumer resolving the published Wasm variant and producing the production webpack JS/Wasm bundle.
- Publication inventory: the root `lets-plot-compose` Gradle module metadata must reference the Desktop, Android, and Wasm target publications.

The final `release-candidate-readiness` CI gate aggregates these checks into a 90-day evidence artifact. See [3.2.3 RC readiness](docs/release/3.2.3-rc-readiness.md) for the full contract.

`release-dependency-preflight` then evaluates whether the release line is actually publishable. While the project or either upstream Lets-Plot dependency is still a SNAPSHOT, CI records a pre-release blocker and prevents a final `3.2.3` version from depending on SNAPSHOT artifacts. See the [3.2.3 release checklist](docs/release/3.2.3-release-checklist.md) for the staged release flow.

A separate signed release-bundle rehearsal exercises the final-version Maven publication path with an ephemeral CI-only signing key and already released compatible upstream artifacts. It validates the local bundle and signatures but never invokes the Central upload task.

### Desktop Graphite Readiness

The production Desktop renderer remains **Native Canvas**.

This fork also carries an **experimental, explicit opt-in Graphite readiness baseline** used by CI to validate the future Compose Desktop/Skiko Graphite path. This probe baseline is intentionally separate from the production Compose 1.12.1 build. The frozen verified probe matrix is:

- Windows x64
- JDK 21
- Compose Multiplatform 1.13.0-alpha01
- Skiko 0.153.0
- optional probe-only Graphite/Vulkan runtime
- `letsplot.compose.desktop.renderPath=graphite-offscreen`

The optional runtime is not part of the normal production dependency graph. Normal production builds are marked Graphite-ineligible and continue on Native Canvas. Unsupported or unverified platform/version combinations are blocked before Graphite/Vulkan initialization; missing runtime or provider failures fall back to Native Canvas.

This baseline is regression evidence, not a commitment to make Graphite the default renderer. Further adoption is intentionally paused until the upstream Compose/Skiko baseline changes.


![Splash](img-2.png)

## Dependencies

- Compose Multiplatform: [1.11.1](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.11.1)
- Lets-Plot Kotlin API: [4.15.0](https://github.com/JetBrains/lets-plot-kotlin/releases/tag/v4.15.0)
- Lets-Plot Multiplatform: [4.11.0](https://github.com/JetBrains/lets-plot/releases/tag/v4.11.0)
                               
- kotlinx-datetime: [0.7.1](https://github.com/Kotlin/kotlinx-datetime/releases/tag/v0.7.1)
- kotlinx-coroutines: [1.8.0](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.8.0)


### Compose Multiplatform for Desktop

```kotlin
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)

    // Lets-Plot Kotlin API
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.0")

    // Optional: contains the PlotImageExport utility which enables exporting to raster formats.
    implementation("org.jetbrains.lets-plot:platf-awt:4.11.0")

    // Lets-Plot Compose UI
    implementation("org.jetbrains.lets-plot:lets-plot-compose:3.2.2")
}
```

See examples: 
- [Compose desktop](https://github.com/JetBrains/lets-plot-compose-demos/blob/main/compose-desktop/build.gradle.kts)
- [Compose multiplatform](https://github.com/JetBrains/lets-plot-compose-demos/blob/main/compose-multiplatform/build.gradle.kts)

> [!TIP]
> The `org.jetbrains.lets-plot:lets-plot-kotlin` dependency transitively brings in 3rd-party runtime dependencies:
> - `org.jetbrains.kotlinx:kotlinx-datetime:0.7.1`
> - `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0`
>
> For a dependency-free configuration (JVM/Desktop target only), replace `lets-plot-kotlin` with the following:
> ```kotlin
> implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:4.15.0")
> implementation("org.jetbrains.lets-plot:lets-plot-common:4.11.0")
> ```


### Compose Multiplatform for Android

```kotlin
dependencies {
    // Lets-Plot Kotlin API
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.0")

    // Lets-Plot Compose UI
    implementation("org.jetbrains.lets-plot:lets-plot-compose:3.2.2")
}
```

See examples:
- [Android minimal](https://github.com/JetBrains/lets-plot-compose-demos/blob/main/compose-android-min/build.gradle.kts)
- [Compose multiplatform](https://github.com/JetBrains/lets-plot-compose-demos/blob/main/compose-multiplatform/build.gradle.kts)
     

### Compose Multiplatform for WasmJS

```kotlin
dependencies {
    // Lets-Plot Kotlin API
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.0")

    // Lets-Plot Compose UI
    implementation("org.jetbrains.lets-plot:lets-plot-compose:3.2.2")
}
```

See examples:
- [Compose multiplatform](https://github.com/JetBrains/lets-plot-compose-demos/blob/main/compose-multiplatform/build.gradle.kts)


## More Examples

You will find complete examples of using **Lets-Plot Kotlin API** with **Lets-Plot Compose Frontend** in the following\
GitHub repository: [JetBrains/lets-plot-compose-demos](https://github.com/JetBrains/lets-plot-compose-demos).

## Change Log

See [CHANGELOG.md](https://github.com/JetBrains/lets-plot-compose/blob/main/CHANGELOG.md).

## Code of Conduct

This project and the corresponding community are governed by the
[JetBrains Open Source and Community Code of Conduct](https://confluence.jetbrains.com/display/ALL/JetBrains+Open+Source+and+Community+Code+of+Conduct).
Please make sure you read it.

## License

Code and documentation released under
the [MIT license](https://github.com/JetBrains/lets-plot-compose/blob/master/LICENSE).
Copyright © 2023, JetBrains s.r.o.
