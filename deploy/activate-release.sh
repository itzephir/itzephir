#!/usr/bin/env bash
set -euo pipefail

target="${1:?Usage: activate-release.sh prod|dev RELEASE}"
release="${2:?Missing release ID}"
case "$target" in
    prod) port=18080; site_host=itzephir.com ;;
    dev) port=18081; site_host=dev.itzephir.com ;;
    *) echo "Invalid environment: $target" >&2; exit 1 ;;
esac
[[ "$release" =~ ^[a-f0-9]{12}-[0-9]+-[0-9]+$ ]] || { echo "Invalid release ID" >&2; exit 1; }

base="${RELEASE_ROOT:-/srv/itzephir}/$target"
candidate="$base/releases/$release"
service="itzephir@$target.service"
[[ -f "$candidate/lib/website.jar" ]] || { echo "Missing website.jar" >&2; exit 1; }
previous="$(readlink -f "$base/current" || true)"

activate() {
    ln -sfn "$1" "$base/current.next"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        mv -fh "$base/current.next" "$base/current"
    else
        mv -Tf "$base/current.next" "$base/current"
    fi
}

rollback() {
    echo "Release failed health checks; restoring $previous" >&2
    if [[ -n "$previous" && -d "$previous" ]]; then
        activate "$previous"
        if [[ -f "$previous/lib/website.jar" ]]; then
            sudo -n /usr/bin/systemctl restart "$service"
        else
            # nginx can still serve the previous Compose/static release.
            sudo -n /usr/bin/systemctl stop "$service"
        fi
    else
        sudo -n /usr/bin/systemctl stop "$service"
        rm -f "$base/current"
    fi
    exit 1
}

activate "$candidate"
sudo -n /usr/bin/systemctl restart "$service" || rollback

healthy=false
for attempt in {1..40}; do
    if [[ "$(curl --silent --fail --max-time 2 "http://127.0.0.1:$port/healthz" || true)" == "ok" ]]; then
        healthy=true
        break
    fi
    sleep 0.5
done
[[ "$healthy" == "true" ]] || rollback
curl --silent --show-error --fail --max-time 10 \
    --resolve "$site_host:443:127.0.0.1" "https://$site_host/" >/dev/null || rollback

# Retain the current and immediately previous releases even if older than a week.
find "$base/releases" -mindepth 1 -maxdepth 1 -type d \
    ! -path "$candidate" ! -path "$previous" -mtime +7 -exec rm -rf -- {} +
echo "Activated $release for $site_host"
