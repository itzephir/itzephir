#!/usr/bin/env bash

set -euo pipefail

distribution_dir="${1:-website/build/dist/wasmJs/productionExecutable}"
index_file="$distribution_dir/index.html"

if [[ ! -f "$index_file" ]]; then
    echo "Missing production index: $index_file" >&2
    exit 1
fi

command -v brotli >/dev/null
command -v gzip >/dev/null

if command -v sha256sum >/dev/null; then
    css_version="$(sha256sum "$distribution_dir/styles.css" | cut -c 1-12)"
else
    css_version="$(shasum -a 256 "$distribution_dir/styles.css" | cut -c 1-12)"
fi

wasm_files=()
while IFS= read -r wasm_file; do
    wasm_files+=("$wasm_file")
done < <(
    find "$distribution_dir" -maxdepth 1 -type f -name '*.wasm' \
        -exec basename {} \; | sort
)

if (( ${#wasm_files[@]} == 0 )); then
    echo "No Wasm files found in $distribution_dir" >&2
    exit 1
fi

preloads_file="$(mktemp)"
index_tmp="$(mktemp)"
trap 'rm -f "$preloads_file" "$index_tmp"' EXIT

for wasm_file in "${wasm_files[@]}"; do
    printf '    <link rel="preload" href="/%s" as="fetch" type="application/wasm" crossorigin>\n' \
        "$wasm_file" >> "$preloads_file"
done

awk -v preloads="$preloads_file" -v css_version="$css_version" '
    /<!-- WASM_PRELOADS -->/ {
        while ((getline line < preloads) > 0) print line
        close(preloads)
        next
    }
    {
        sub(/href="styles.css"/, "href=\"styles.css?v=" css_version "\"")
        print
    }
' "$index_file" > "$index_tmp"

install -m 644 "$index_tmp" "$index_file"
rm -f "$distribution_dir"/*.map

while IFS= read -r -d '' asset; do
    brotli --force --quality=11 "$asset"
    gzip -9 -c "$asset" > "$asset.gz"
done < <(
    find "$distribution_dir" -maxdepth 1 -type f \
        \( -name '*.html' -o -name '*.css' -o -name '*.js' -o \
           -name '*.wasm' -o -name '*.svg' \) -print0
)

printf 'Prepared %d Wasm preload(s).\n' "${#wasm_files[@]}"
du -ch "$distribution_dir"/*.wasm "$distribution_dir"/*.wasm.br | tail -n 1
