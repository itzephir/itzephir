#!/usr/bin/env bash
set -euo pipefail

[[ "$EUID" == "0" ]] || { echo "Run as root on the deployment VM" >&2; exit 1; }
cd "$(dirname "$0")"
id deploy >/dev/null
if ! dpkg-query -W openjdk-21-jre-headless >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless
fi

install -d -m 755 /etc/itzephir
printf 'BIND_HOST=127.0.0.1\nPORT=18080\nCOOKIE_SECURE=true\n' > /etc/itzephir/prod.env
printf 'BIND_HOST=127.0.0.1\nPORT=18081\nCOOKIE_SECURE=true\n' > /etc/itzephir/dev.env
chmod 644 /etc/itzephir/{prod,dev}.env
install -m 644 systemd/itzephir@.service /etc/systemd/system/itzephir@.service

sudoers_file="$(mktemp)"
trap 'rm -f "$sudoers_file"' EXIT
cat > "$sudoers_file" <<'SUDOERS'
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart itzephir@dev.service, /usr/bin/systemctl stop itzephir@dev.service, /usr/bin/systemctl restart itzephir@prod.service, /usr/bin/systemctl stop itzephir@prod.service
deploy ALL=(root) NOPASSWD: /usr/local/sbin/itzephir-apply-nginx dev *, /usr/local/sbin/itzephir-apply-nginx prod *
SUDOERS
visudo -cf "$sudoers_file"
install -m 440 "$sudoers_file" /etc/sudoers.d/itzephir-deploy

install -m 755 apply-nginx.py /usr/local/sbin/itzephir-apply-nginx
# Split an existing site without changing either environment's behavior.
# On a fresh VM, use the checked-in Ktor templates.
ln -sfn /etc/nginx/sites-available/itzephir.com /etc/nginx/sites-enabled/itzephir.com
/usr/local/sbin/itzephir-apply-nginx --bootstrap "$PWD/nginx"
systemctl daemon-reload
systemctl enable itzephir@dev.service itzephir@prod.service
