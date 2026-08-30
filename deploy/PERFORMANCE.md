# Startup measurements

The second startup pass removes the render-blocking CSS request, gives the entry
script a content-hashed URL so nginx can cache it, and replaces the fixed 250 ms
handoff delay with two animation frames. The UI remains Compose Multiplatform
on Wasm; the existing HTML first screen stays visible during initialization.

## Local comparison, 2026-08-30

`benchmark-startup.mjs` compared the release before these changes with the new
prepared release in isolated Chrome contexts. Network: 10 Mbps, 100 ms latency;
CPU: 4x slowdown. Values below are medians of three runs; build order alternated.
Warm runs reuse the corresponding cold run's browser cache.

| Metric | Before | After |
| --- | ---: | ---: |
| Cold first contentful paint | 320 ms | 192 ms |
| Cold Compose-ready marker | 3698 ms | 3622 ms |
| Warm first contentful paint | 208 ms | 196 ms |
| Warm Compose-ready marker | 735 ms | 538 ms |
| Warm entry-script transfer | 80,949 bytes | 0 bytes (cached) |
| Separate startup stylesheet request | 1 | 0 |

The Compose-ready marker is the start of the HTML-to-canvas fade, not a measured
completion of every rendered frame. Browser screenshots check the resulting UI
separately. First paint is not full interactivity.

The two Wasm files still total about 3.15 MB with Brotli. Therefore full cold
startup is still predominantly a download cost; this pass improves early content
and repeat visits, not the fundamental runtime size. These local measurements
are comparative, not a promise of real-world load times. Server latency, browser,
device, and bandwidth can change the result.
