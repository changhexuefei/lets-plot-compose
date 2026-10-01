# Compose Multiplatform Consumer Contract Readiness Checklist

This checklist defines the release boundary for `lets-plot-compose`.

## Public API Boundary

- [x] Consumers depend only on Compose-facing APIs.
- [x] Renderer implementations remain internal.
- [x] Skiko and backend-specific APIs are not exposed.

## Lifecycle Contract

Required regression coverage:

- [ ] recomposition
- [ ] plot replacement
- [ ] window recreation
- [ ] resource disposal
- [ ] configuration changes

## Platform Contract

| Target | Validation |
| --- | --- |
| Desktop JVM | Consumer smoke required |
| Android | Published variant resolution and runtime smoke required |
| WasmJS | Production bundle and browser smoke required |

## Release Candidate Gate

Before publishing a release candidate:

- [ ] standalone external consumer build
- [ ] published artifact resolution
- [ ] rendering regression evidence
- [ ] interaction regression evidence
- [ ] lifecycle regression evidence
- [ ] screenshot artifacts retained

## Renderer Policy

Production renderer changes require a separate compatibility review.

Graphite readiness experiments remain evidence-only until upstream Compose and Skiko compatibility is established.
