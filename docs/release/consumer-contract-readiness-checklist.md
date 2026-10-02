# Compose Multiplatform Consumer Contract Readiness Checklist

This checklist defines the release boundary for `lets-plot-compose`.

## Public API Boundary

- [x] Consumers depend only on Compose-facing APIs.
- [x] Renderer implementations remain internal.
- [x] Skiko and backend-specific APIs are not exposed.

## Lifecycle Contract

Verified by the current release gate:

- [x] explicit Desktop recomposition probe without resource disposal
- [x] Desktop resource-disposal assertion when the consumer leaves composition
- [x] Android Activity recreation probe with non-blank render after recreation
- [x] Android previous-activity PlotFigureModel disposal assertion during recreation
- [x] plot replacement and FigureModel reconnect on Desktop, Android, and WasmJS
- [x] Desktop window close/reopen creates a new window instance
- [x] computation-message redispatch after plot replacement/restoration

The dedicated `consumer-lifecycle-hardening` CI gate aggregates the Desktop and Android runtime evidence into a 90-day artifact. Lifecycle claims must be backed by executable assertions before they are promoted to `PASS`.

## Platform Contract

| Target | Validation |
| --- | --- |
| Desktop JVM | Published consumer smoke on Windows/JDK 21, explicit recomposition/disposal probes, plus 9 screenshot checkpoints |
| Android | Published variant resolution, release AAR, API 34 emulator runtime smoke, and Activity recreation/disposal probe |
| WasmJS | Published variant resolution, production bundle, and browser interaction smoke |

## Release Candidate Gate

Before publishing a release candidate:

- [x] standalone external consumer build
- [x] published artifact resolution
- [x] rendering regression evidence
- [x] interaction regression evidence
- [x] lifecycle hardening evidence
- [x] screenshot artifacts retained for 90 days

## Renderer Policy

Production renderer changes require a separate compatibility review.

Graphite readiness experiments remain evidence-only until upstream Compose and Skiko compatibility is established.
