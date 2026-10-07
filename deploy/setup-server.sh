#!/usr/bin/env bash
set -euo pipefail

[[ "$EUID" == "0" ]] || { echo "Run as root on the deployment VM" >&2; exit 1; }
cd "$(dirname "$0")"
id deploy >/dev/null
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless

install -d -m 755 /etc/itzephir
printf 'BIND_HOST=127.0.0.1\nPORT=18080\nCOOKIE_SECURE=true\n' > /etc/itzephir/prod.env
printf 'BIND_HOST=127.0.0.1\nPORT=18081\nCOOKIE_SECURE=true\n' > /etc/itzephir/dev.env
chmod 644 /etc/itzephir/{prod,dev}.env
install -m 644 systemd/itzephir@.service /etc/systemd/system/itzephir@.service

sudoers_file="$(mktemp)"
nginx_backup="$(mktemp)"
trap 'rm -f "$sudoers_file" "$nginx_backup"' EXIT
cat > "$sudoers_file" <<'SUDOERS'
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart itzephir@dev.service, /usr/bin/systemctl stop itzephir@dev.service, /usr/bin/systemctl restart itzephir@prod.service, /usr/bin/systemctl stop itzephir@prod.service
SUDOERS
visudo -cf "$sudoers_file"
install -m 440 "$sudoers_file" /etc/sudoers.d/itzephir-deploy

# Keep serving current static releases until CI activates a Ktor distribution.
cp /etc/nginx/sites-available/itzephir.com "$nginx_backup"
install -m 644 nginx/itzephir.com.conf /etc/nginx/sites-available/itzephir.com
if ! nginx -t; then
    cp "$nginx_backup" /etc/nginx/sites-available/itzephir.com
    exit 1
fi
systemctl daemon-reload
systemctl enable itzephir@dev.service itzephir@prod.service
systemctl reload nginx
