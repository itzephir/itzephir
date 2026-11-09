#!/usr/bin/env bash
# The only provisioning entry point used by the main-only infrastructure workflow.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root' >&2; exit 1; }
target="${1:?Usage: prepare-project-routing.sh dev|prod TOKEN_FILE [SLUG]}"
[[ "$target" == dev || "$target" == prod ]] || { echo 'Invalid environment' >&2; exit 1; }
token_file="${2:?Missing private Timeweb DNS token file}"
slug="${3:-}"
cd "$(dirname "$0")"
exec 8>/run/lock/itzephir-project-routing.lock
flock -x 8
# Fail before issuing certificates or changing Docker if nginx is already invalid.
nginx -t
docker info >/dev/null
if [[ -n "$slug" ]]; then
    test -s "/etc/letsencrypt/live/itzephir-projects-$target/fullchain.pem"
    grep -Fq '# Managed project subdomains v2 start' "/etc/nginx/itzephir/$target.conf" || { echo 'Enable base project routing before acquiring a namespace' >&2; exit 1; }
    python3 setup-project-namespace-dns.py "$target" "$slug" "$token_file"
fi
bash setup-project-certificates.sh "$target" "$token_file" "$slug"
if [[ -n "$slug" ]]; then
    # Publish only the namespace gateway; project containers and targets stay project-owned.
    install -d -m 755 /usr/local/sbin
    install -m 755 apply-nginx.py /usr/local/sbin/itzephir-apply-nginx.next
    mv -f /usr/local/sbin/itzephir-apply-nginx.next /usr/local/sbin/itzephir-apply-nginx
    /usr/local/sbin/itzephir-apply-nginx --activate-namespace "$target" "$slug"
else
    bash setup-project-routing.sh "$target"
fi
/usr/local/sbin/itzephir-apply-nginx --check-routing "$target"
nginx -t
if [[ -n "$slug" ]]; then
    echo "Acquired namespace $slug for $target: DNS, wildcard TLS, renewal and active gateway."
else
    echo "Prepared $target: certificate, Docker DNS and nginx publisher. Activate through normal deployment."
fi
