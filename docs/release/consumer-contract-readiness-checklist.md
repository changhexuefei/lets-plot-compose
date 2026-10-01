# Compose Multiplatform Consumer Contract Readiness Checklist

This checklist defines the release boundary for `lets-plot-compose`.

## Public API Boundary

- [x] Consumers depend only on Compose-facing APIs.
- [x] Renderer implementations remain internal.
- [x] Skiko and backend-specific APIs are not exposed.

## Lifecycle Contract

Verified by the current release gate:

- [x] plot replacement and FigureModel reconnect on Desktop, Android, and WasmJS
- [x] Desktop window close/reopen creates a new window instance
- [x] computation-message redispatch after plot replacement/restoration

Follow-up lifecycle hardening is tracked separately and must not be reported as passing evidence until dedicated runtime checks exist:

- [ ] explicit recomposition lifecycle probe
- [ ] explicit resource-disposal counter/assertion
- [ ] Android configuration-change recreation probe

## Platform Contract

| Target | Validation |
| --- | --- |
| Desktop JVM | Published consumer smoke on Windows/JDK 21 plus 9 screenshot checkpoints |
| Android | Published variant resolution, release AAR, and API 34 emulator runtime smoke |
| WasmJS | Published variant resolution, production bundle, and browser interaction smoke |

## Release Candidate Gate

Before publishing a release candidate:

- [x] standalone external consumer build
- [x] published artifact resolution
- [x] rendering regression evidence
- [x] interaction regression evidence
- [x] currently verified lifecycle/replacement evidence
- [x] screenshot artifacts retained for 90 days

The release gate must describe only evidence that is actually asserted by CI. Unimplemented lifecycle probes stay unchecked and are not promoted to `PASS` in release artifacts.

## Renderer Policy

Production renderer changes require a separate compatibility review.

Graphite readiness experiments remain evidence-only until upstream Compose and Skiko compatibility is established.
