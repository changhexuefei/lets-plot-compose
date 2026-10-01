# Compose Multiplatform Consumer Contract

## Purpose

This document defines the supported consumer boundary for `lets-plot-compose`.
The library exposes a stable Compose Multiplatform API surface while keeping renderer implementation details internal.

## Supported Consumer Model

Consumers should only depend on:

- `PlotPanel`
- plot specification objects
- Compose modifiers
- documented interaction callbacks

Consumers should not depend on:

- Skiko internals
- renderer implementations
- platform canvas objects
- experimental backend APIs

## Primary API Contract

The minimal usage remains:

```kotlin
PlotPanel(figure = myPlot)
```

Optional customization:

```kotlin
PlotPanel(
    figure = myPlot,
    modifier = Modifier.fillMaxSize()
)
```

## Lifecycle Expectations

The component must correctly handle:

- Compose recomposition
- window recreation
- configuration changes
- disposal of resources
- plot replacement

Resource ownership stays inside the library boundary.

## Platform Matrix

| Target | Contract |
| --- | --- |
| Desktop JVM | Supported |
| Android | Supported |
| WasmJS | Supported |

## Renderer Policy

The production rendering path remains the current verified renderer.

Future renderer experiments must not change the public consumer contract.

Graphite readiness probes are evidence-only and are not part of the production API.

## Release Criteria

A release candidate must validate:

- standalone consumer build
- published artifact resolution
- rendering regression
- interaction regression
- lifecycle regression
- multiplatform compilation
