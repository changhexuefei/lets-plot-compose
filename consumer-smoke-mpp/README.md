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
