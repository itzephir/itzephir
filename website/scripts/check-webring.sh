#!/usr/bin/env bash

set -euo pipefail

readonly SITE_ROOT="${1:-website/build/dist/wasmJs/productionExecutable}"
readonly TEST_HOST="127.0.0.1"
readonly TEST_PORT="4173"
readonly TEST_URL="http://$TEST_HOST:$TEST_PORT"
readonly PREVIOUS_URL="https://webring.otomir23.me/itzephir/prev"
readonly WEBRING_URL="https://webring.otomir23.me/"
readonly NEXT_URL="https://webring.otomir23.me/itzephir/next"

if [[ ! -f "$SITE_ROOT/index.html" ]]; then
    echo "Missing website artifact at $SITE_ROOT/index.html" >&2
    exit 1
fi

server_log="$(mktemp)"
python3 -m http.server "$TEST_PORT" \
    --bind "$TEST_HOST" \
    --directory "$SITE_ROOT" \
    >"$server_log" 2>&1 &
server_pid=$!

cleanup() {
    kill "$server_pid" 2>/dev/null || true
    rm -f "$server_log"
}
trap cleanup EXIT

for attempt in {1..20}; do
    if curl --silent --fail --output /dev/null "$TEST_URL/"; then
        break
    fi

    if [[ "$attempt" == "20" ]]; then
        echo "Packaged website did not start" >&2
        sed -n '1,120p' "$server_log" >&2
        exit 1
    fi

    sleep 0.25
done

page_html="$(curl --silent --show-error --fail "$TEST_URL/")"
page_css="$(curl --silent --show-error --fail "$TEST_URL/styles.css")"

assert_contains() {
    local content="$1"
    local expected="$2"
    local description="$3"

    if ! grep -Fq "$expected" <<<"$content"; then
        echo "Missing $description" >&2
        exit 1
    fi
}

assert_contains "$page_html" 'class="shell-header-actions"' "webring header group"
assert_contains "$page_html" 'class="shell-webring"' "webring navigation"
assert_contains "$page_html" "href=\"$PREVIOUS_URL\"" "previous-site link"
assert_contains "$page_html" "href=\"$WEBRING_URL\"" "webring index link"
assert_contains "$page_html" "href=\"$NEXT_URL\"" "next-site link"
assert_contains "$page_css" '.shell-header-actions' "webring header layout"
assert_contains "$page_css" '.shell-webring' "webring styles"

echo "Packaged website webring integration passed"
