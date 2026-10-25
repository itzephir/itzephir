#!/usr/bin/env bash
# The only provisioning entry point used by the main-only infrastructure workflow.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root' >&2; exit 1; }
target="${1:?Usage: prepare-project-routing.sh dev|prod TOKEN_FILE}"
[[ "$target" == dev || "$target" == prod ]] || { echo 'Invalid environment' >&2; exit 1; }
token_file="${2:?Missing private Timeweb DNS token file}"
cd "$(dirname "$0")"
exec 8>/run/lock/itzephir-project-routing.lock
flock -x 8
# Fail before issuing certificates or changing Docker if nginx is already invalid.
nginx -t
docker info >/dev/null
bash setup-project-certificates.sh "$target" "$token_file"
bash setup-project-routing.sh "$target"
/usr/local/sbin/itzephir-apply-nginx --check-routing "$target"
nginx -t
# Configuration activation stays in the normal, rollback-capable application deployment.
echo "Prepared $target: certificate, Docker DNS and nginx publisher. Activate through normal deployment."
