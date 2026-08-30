#!/usr/bin/env bash
set -euo pipefail

# Keep the existing local/CI entry point; Node provides Brotli and gzip on both
# macOS and Linux without installing a separate compression package.
exec node "$(dirname "$0")/prepare-web-release.mjs" \
    "${1:-website/build/dist/wasmJs/productionExecutable}"
