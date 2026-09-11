#!/usr/bin/env bash

set -euo pipefail

readonly BASE_URL="https://webring.otomir23.me"
readonly SITE_SLUG="itzephir"
readonly SITE_URL="https://itzephir.com/"
readonly USER_AGENT="itzephir-webring-ci/1.0 (+https://itzephir.com/)"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INDEX_HTML="$SCRIPT_DIR/../src/wasmJsMain/resources/index.html"

curl_args=(
    --silent
    --show-error
    --fail
    --retry 3
    --retry-delay 2
    --retry-all-errors
    --connect-timeout 10
    --max-time 30
    --proto '=https'
    --proto-redir '=https'
    --user-agent "$USER_AGENT"
)

normalize_url() {
    printf '%s' "${1%/}"
}

for fallback_url in \
    "$BASE_URL/$SITE_SLUG/prev" \
    "$BASE_URL/" \
    "$BASE_URL/$SITE_SLUG/next"; do
    if ! grep -Fq "href=\"$fallback_url\"" "$INDEX_HTML"; then
        echo "Loading fallback does not link to $fallback_url" >&2
        exit 1
    fi
done

central_status="$({
    curl "${curl_args[@]}" \
        --location \
        --max-redirs 10 \
        --output /dev/null \
        --write-out '%{http_code}' \
        "$BASE_URL/"
})"

if [[ ! "$central_status" =~ ^2[0-9]{2}$ ]]; then
    echo "Webring index returned HTTP $central_status" >&2
    exit 1
fi

site_data="$(curl "${curl_args[@]}" "$BASE_URL/$SITE_SLUG/data")"

jq -e \
    --arg slug "$SITE_SLUG" \
    --arg site_url "$SITE_URL" \
    '
        def nonempty: type == "string" and length > 0;

        .curr.slug == $slug and
        ((.curr.url | rtrimstr("/")) == ($site_url | rtrimstr("/"))) and
        (.prev.slug | nonempty) and
        (.prev.name | nonempty) and
        (.prev.url | nonempty) and
        (.next.slug | nonempty) and
        (.next.name | nonempty) and
        (.next.url | nonempty) and
        (.prev.slug != .curr.slug) and
        (.next.slug != .curr.slug)
    ' <<<"$site_data" >/dev/null

check_direction() {
    local direction="$1"
    local expected_url
    local redirect_result
    local redirect_status
    local redirect_url
    local final_result
    local final_status
    local final_url

    expected_url="$(jq -r --arg direction "$direction" '.[$direction].url' <<<"$site_data")"
    redirect_result="$({
        curl "${curl_args[@]}" \
            --output /dev/null \
            --write-out '%{http_code}\n%{redirect_url}' \
            "$BASE_URL/$SITE_SLUG/$direction"
    })"
    redirect_status="${redirect_result%%$'\n'*}"
    redirect_url="${redirect_result#*$'\n'}"

    if [[ "$redirect_status" != "302" ]]; then
        echo "Webring $direction endpoint returned HTTP $redirect_status instead of 302" >&2
        exit 1
    fi

    if [[ "$(normalize_url "$redirect_url")" != "$(normalize_url "$expected_url")" ]]; then
        echo "Webring $direction endpoint redirects to $redirect_url instead of $expected_url" >&2
        exit 1
    fi

    final_result="$({
        curl "${curl_args[@]}" \
            --location \
            --max-redirs 10 \
            --output /dev/null \
            --write-out '%{http_code}\n%{url_effective}' \
            "$BASE_URL/$SITE_SLUG/$direction"
    })"
    final_status="${final_result%%$'\n'*}"
    final_url="${final_result#*$'\n'}"

    if [[ ! "$final_status" =~ ^2[0-9]{2}$ ]]; then
        echo "Following the $direction link ended at $final_url with HTTP $final_status" >&2
        exit 1
    fi

    echo "Verified $direction navigation to $final_url"
}

check_direction prev
check_direction next

echo "Webring integration check passed"
