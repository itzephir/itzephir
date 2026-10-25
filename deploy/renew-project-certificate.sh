#!/usr/bin/env bash
# Certbot deploy hook: share the publisher lock, validate first, then reload nginx.
set -euo pipefail
case "${RENEWED_LINEAGE:-}" in
    /etc/letsencrypt/live/itzephir-projects-dev|/etc/letsencrypt/live/itzephir-projects-prod) ;;
    *) exit 0 ;;
esac
exec 9>/run/lock/itzephir-nginx.lock
flock -x 9
nginx -t
systemctl reload nginx
