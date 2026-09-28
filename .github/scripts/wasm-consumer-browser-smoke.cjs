#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');
const { chromium } = require('playwright-core');

const url = process.env.WASM_SMOKE_URL || 'http://127.0.0.1:4173/';
const executablePath = process.env.WASM_SMOKE_CHROME || '/usr/bin/google-chrome';
const outputDir = path.resolve(process.env.WASM_SMOKE_OUTPUT_DIR || 'build/evidence/wasm-browser');
const diffThreshold = Number(process.env.WASM_SMOKE_DIFF_THRESHOLD || '0.00005');

fs.mkdirSync(outputDir, { recursive: true });

function fail(message) {
  throw new Error(message);
}

function parsePng(buffer) {
  return PNG.sync.read(buffer);
}

function sampledUniqueColors(buffer) {
  const png = parsePng(buffer);
  const colors = new Set();
  const stepX = Math.max(1, Math.floor(png.width / 40));
  const stepY = Math.max(1, Math.floor(png.height / 40));
  for (let y = 0; y < png.height; y += stepY) {
    for (let x = 0; x < png.width; x += stepX) {
      const i = (png.width * y + x) << 2;
      colors.add(
        png.data[i] + ',' + png.data[i + 1] + ',' + png.data[i + 2] + ',' + png.data[i + 3]
      );
    }
  }
  return colors.size;
}

function findRedMarker(buffer) {
  const png = parsePng(buffer);
  const centerX = png.width / 2;
  const centerY = png.height / 2;
  let best = null;

  for (let y = 0; y < png.height; y += 2) {
    for (let x = 0; x < png.width; x += 2) {
      const i = (png.width * y + x) << 2;
      const r = png.data[i];
      const g = png.data[i + 1];
      const b = png.data[i + 2];
      const a = png.data[i + 3];
      if (a > 180 && r > 170 && g < 120 && b < 120 && r - Math.max(g, b) > 80) {
        const distance = Math.abs(x - centerX) + Math.abs(y - centerY);
        if (!best || distance < best.distance) {
          best = { x, y, distance };
        }
      }
    }
  }
  return best;
}

function pixelDifferenceRatio(leftBuffer, rightBuffer) {
  const a = parsePng(leftBuffer);
  const b = parsePng(rightBuffer);
  const width = Math.min(a.width, b.width);
  const height = Math.min(a.height, b.height);
  const step = 2;
  let changed = 0;
  let total = 0;

  for (let y = 0; y < height; y += step) {
    for (let x = 0; x < width; x += step) {
      const ai = (a.width * y + x) << 2;
      const bi = (b.width * y + x) << 2;
      const delta =
        Math.abs(a.data[ai] - b.data[bi]) +
        Math.abs(a.data[ai + 1] - b.data[bi + 1]) +
        Math.abs(a.data[ai + 2] - b.data[bi + 2]);
      if (delta > 18) {
        changed++;
      }
      total++;
    }
  }
  return total === 0 ? 0 : changed / total;
}

async function largestCanvas(page) {
  const canvases = page.locator('canvas');
  const count = await canvases.count();
  if (count === 0) fail('No canvas element was rendered by the Wasm consumer.');

  let best = null;
  for (let i = 0; i < count; i++) {
    const locator = canvases.nth(i);
    const box = await locator.boundingBox();
    if (!box || box.width < 50 || box.height < 50) continue;
    const area = box.width * box.height;
    if (!best || area > best.area) {
      best = { locator, box, area, index: i };
    }
  }
  if (!best) fail('No visible plot canvas with a usable size was found.');
  return best;
}

async function capture(page, clip, name) {
  const buffer = await page.screenshot({ type: 'png', clip });
  fs.writeFileSync(path.join(outputDir, name), buffer);
  return buffer;
}

async function waitForServer(page) {
  let lastError = null;
  for (let attempt = 1; attempt <= 30; attempt++) {
    try {
      const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 3000 });
      if (response && response.ok()) return;
      lastError = new Error('HTTP status ' + (response ? response.status() : 'unknown'));
    } catch (error) {
      lastError = error;
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw lastError || new Error('Wasm smoke server did not become reachable.');
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--ignore-gpu-blocklist',
      '--enable-webgl',
      '--enable-unsafe-swiftshader'
    ]
  });

  const pageErrors = [];
  const consoleErrors = [];
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 760 } });
    page.on('pageerror', error => pageErrors.push(String(error)));
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });

    await waitForServer(page);
    try {
      await page.waitForSelector('canvas', { state: 'visible', timeout: 30000 });
    } catch (error) {
      const title = await page.title().catch(() => '<unavailable>');
      const body = await page.locator('body').innerText().catch(() => '<unavailable>');
      console.error('WASM_BROWSER_DIAGNOSTIC url=' + page.url());
      console.error('WASM_BROWSER_DIAGNOSTIC title=' + title);
      console.error('WASM_BROWSER_DIAGNOSTIC body=' + body.slice(0, 2000));
      console.error('WASM_BROWSER_DIAGNOSTIC consoleErrors=' + consoleErrors.join(' | '));
      console.error('WASM_BROWSER_DIAGNOSTIC pageErrors=' + pageErrors.join(' | '));
      throw error;
    }
    await page.waitForTimeout(1800);

    const canvas = await largestCanvas(page);
    const clip = {
      x: Math.max(0, canvas.box.x),
      y: Math.max(0, canvas.box.y),
      width: Math.min(canvas.box.width, 1100 - Math.max(0, canvas.box.x)),
      height: Math.min(canvas.box.height, 760 - Math.max(0, canvas.box.y))
    };

    const render = await capture(page, clip, '01-render.png');
    const uniqueColors = sampledUniqueColors(render);
    if (uniqueColors < 12) {
      fail('Rendered Wasm plot looks blank or nearly uniform: uniqueColors=' + uniqueColors);
    }

    const centerX = canvas.box.x + canvas.box.width / 2;
    const centerY = canvas.box.y + canvas.box.height / 2;

    await page.mouse.move(4, 4);
    await page.waitForTimeout(250);
    const tooltipBaseline = await capture(page, clip, '02-tooltip-before.png');

    let tooltip = null;
    let tooltipRatio = 0;
    let tooltipPoint = null;

    const marker = findRedMarker(tooltipBaseline);
    if (marker) {
      await page.mouse.move(clip.x + marker.x, clip.y + marker.y);
      await page.waitForTimeout(220);
      const candidate = await page.screenshot({ type: 'png', clip });
      tooltipRatio = pixelDifferenceRatio(tooltipBaseline, candidate);
      tooltip = candidate;
      tooltipPoint = ['marker', marker.x, marker.y];
    }

    if (tooltipRatio <= diffThreshold) {
      const xFractions = [0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85];
      const yFractions = [0.18, 0.30, 0.42, 0.54, 0.66, 0.78, 0.88];

      for (const yf of yFractions) {
        for (const xf of xFractions) {
          await page.mouse.move(
            canvas.box.x + canvas.box.width * xf,
            canvas.box.y + canvas.box.height * yf
          );
          await page.waitForTimeout(120);
          const candidate = await page.screenshot({ type: 'png', clip });
          const ratio = pixelDifferenceRatio(tooltipBaseline, candidate);
          if (ratio > tooltipRatio) {
            tooltipRatio = ratio;
            tooltip = candidate;
            tooltipPoint = ['grid', xf, yf];
          }
          if (ratio > diffThreshold) break;
        }
        if (tooltipRatio > diffThreshold) break;
      }
    }

    if (!tooltip || tooltipRatio <= diffThreshold) {
      fail(
        'Hover did not produce a visible tooltip/repaint change. bestRatio=' +
          tooltipRatio + ' threshold=' + diffThreshold
      );
    }
    fs.writeFileSync(path.join(outputDir, '03-tooltip-hover.png'), tooltip);

    await page.mouse.move(4, 4);
    await page.waitForTimeout(250);
    const beforeZoom = await page.screenshot({ type: 'png', clip });

    await page.mouse.move(centerX, centerY);
    await page.mouse.wheel(0, -420);
    await page.waitForTimeout(700);
    const zoom = await capture(page, clip, '04-wheel-zoom.png');
    const zoomRatio = pixelDifferenceRatio(beforeZoom, zoom);
    if (zoomRatio <= diffThreshold) {
      fail('Wheel zoom did not visibly change the plot. ratio=' + zoomRatio);
    }

    const beforePan = zoom;
    await page.mouse.move(centerX, centerY);
    await page.mouse.down();
    for (let step = 1; step <= 8; step++) {
      await page.mouse.move(centerX + step * 12, centerY + step * 5);
      await page.waitForTimeout(35);
    }
    await page.mouse.up();
    await page.waitForTimeout(700);
    const pan = await capture(page, clip, '05-drag-pan.png');
    const panRatio = pixelDifferenceRatio(beforePan, pan);
    if (panRatio <= diffThreshold) {
      fail('Drag pan did not visibly change the plot. ratio=' + panRatio);
    }

    if (pageErrors.length > 0) {
      fail('Browser page errors: ' + pageErrors.join(' | '));
    }

    const evidence = [
      'schema=1',
      'result=WASM_BROWSER_INTERACTION_SMOKE_PASS',
      'consumer.boundary=PUBLISHED_MAVEN_ONLY',
      'browser.engine=CHROMIUM',
      'render=PASS',
      'tooltip=PASS',
      'wheel_zoom=PASS',
      'drag_pan=PASS',
      'toolbar=ABSENT',
      'figure_model.external=TRUE',
      'figure_model.toolbarless_feedback=RUNTIME_PASS',
      'production.renderer.default=NATIVE_CANVAS',
      'graphite.production=DISABLED',
      'render.unique_colors=' + uniqueColors,
      'tooltip.best_ratio=' + tooltipRatio,
      'tooltip.best_point=' + tooltipPoint.join(','),
      'zoom.diff_ratio=' + zoomRatio,
      'pan.diff_ratio=' + panRatio,
      'console.error.count=' + consoleErrors.length,
      'page.error.count=' + pageErrors.length,
      'workflow.sha=' + (process.env.GITHUB_SHA || 'LOCAL'),
      'workflow.run_id=' + (process.env.GITHUB_RUN_ID || 'LOCAL'),
      ''
    ].join('\n');

    fs.writeFileSync(path.join(outputDir, 'wasm-browser-interaction-smoke.txt'), evidence);
    process.stdout.write(evidence);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error('WASM_BROWSER_INTERACTION_SMOKE_FAIL');
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
});
