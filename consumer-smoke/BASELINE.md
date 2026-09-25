# Graphite / Skiko Upgrade Baseline

This directory defines the stable visual evidence contract used when upgrading Compose Multiplatform, Skiko, Skia, or a future Graphite-backed rendering path.

## Canonical baseline

The canonical CI baseline is:

```text
windows-jdk21-software
```

It intentionally keeps the current software renderer as the stable control. A renderer experiment must use a different baseline ID; it must not silently replace the control baseline.

## Required evidence

Every successful baseline artifact must contain exactly these nine checkpoint screenshots:

```text
01-render.png
02-resize.png
03-tooltip-before.png
04-tooltip-hover.png
05-zoom.png
06-pan.png
07-density-1.25.png
08-density-1.5.png
09-reopen.png
```

It must also contain:

- `smoke-summary.txt`
- `baseline-manifest.txt`
- `renderer-dependencies.txt`
- the Core and frontend publication manifests produced by compatibility CI

The smoke summary must contain `result=PASS`.

## Comparison contract

1. Compare artifacts only when `baseline.id` matches. Different renderer paths are separate baselines.
2. Compare screenshots by checkpoint filename, never by artifact ordering.
3. Missing checkpoints, a changed checkpoint count, or a non-PASS smoke summary are hard failures.
4. Screenshot dimensions must be checked before visual comparison. A dimension change is a review item, not an automatic visual match.
5. PNG SHA-256 values are provenance fingerprints only. A hash change does not by itself mean a regression because rasterization, antialiasing, fonts, or the rendering backend may legitimately change.
6. For Skiko / Skia / Graphite upgrades, review the same checkpoint pairs first: render, resize, tooltip, zoom, pan, density 1.25x, density 1.5x, and reopen.
7. Interaction behavior remains gated by the executable smoke assertions. Visual evidence supplements those assertions; it does not replace them.
8. The stable software baseline must remain available while a new renderer baseline is evaluated. Only a deliberate follow-up change may promote a new renderer to the canonical control.

## Artifact naming

Long-lived evidence uses:

```text
graphite-skiko-baseline-<baseline-id>-<commit-sha>
```

The current retention period is 90 days. Maven transport bundles remain short-lived and are not visual baselines.

## Upgrade workflow

For an upgrade PR:

1. keep `windows-jdk21-software` running as the control;
2. record the resolved renderer dependencies in `renderer-dependencies.txt`;
3. if testing another rendering path, assign it a distinct baseline ID;
4. compare the new evidence against the previous artifact with the same baseline ID and checkpoint filenames;
5. investigate missing checks, behavior failures, unexpected geometry changes, blank rendering, clipping, DPI regressions, or lifecycle regressions before accepting the upgrade.

This contract is deliberately renderer-neutral so it can survive changes in how Skiko exposes Skia/Graphite backends.
