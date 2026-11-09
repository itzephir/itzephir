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
fi
bash setup-project-certificates.sh "$target" "$token_file" "$slug"
if [[ -n "$slug" ]]; then
    # Namespace enrollment changes no project containers, targets or active nginx files.
    # Base routing must already have been prepared for this environment.
    install -d -m 755 /usr/local/sbin
    install -m 755 apply-nginx.py /usr/local/sbin/itzephir-apply-nginx.next
    mv -f /usr/local/sbin/itzephir-apply-nginx.next /usr/local/sbin/itzephir-apply-nginx
    /usr/local/sbin/itzephir-apply-nginx --prepare-namespace "$target" "$slug"
else
    bash setup-project-routing.sh "$target"
fi
/usr/local/sbin/itzephir-apply-nginx --check-routing "$target"
nginx -t
# Configuration activation stays in the normal, rollback-capable application deployment.
echo "Prepared $target: certificate, Docker DNS and nginx publisher. Activate through normal deployment."
