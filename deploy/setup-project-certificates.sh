#!/usr/bin/env bash
# Install Certbot DNS automation and issue one stand's certificate on the VM.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root' >&2; exit 1; }
target="${1:?Usage: setup-project-certificates.sh dev|prod TOKEN_FILE}"
case "$target" in
    prod) domain=itzephir.com ;;
    dev) domain=dev.itzephir.com ;;
    *) echo 'Invalid environment' >&2; exit 1 ;;
esac
token_file="${2:?Missing private Timeweb DNS token file}"
for command in certbot python3 dig openssl nginx systemctl flock; do command -v "$command" >/dev/null; done
cd "$(dirname "$0")"

# Validate the supplied token before touching installed credentials. Never use argv for its value.
python3 - "$token_file" <<'TOKEN_CHECK'
import importlib.util
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location("dns_hook", "timeweb-dns-hook.py")
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)
hook.read_token(Path(sys.argv[1]))
TOKEN_CHECK

# Reuse the existing production ACME account; ACME_EMAIL is only needed on a fresh VM.
account_args=()
if [[ -n "${ACME_EMAIL:-}" ]]; then
    account_args=(--email "$ACME_EMAIL" --agree-tos)
else
    [[ -f /etc/letsencrypt/renewal/itzephir.com.conf ]] || { echo 'Set ACME_EMAIL on a fresh VM' >&2; exit 1; }
    account="$(sed -n 's/^account *= *\([a-f0-9]\{32,64\}\) *$/\1/p' /etc/letsencrypt/renewal/itzephir.com.conf)"
    [[ "$account" =~ ^[a-f0-9]{32,64}$ ]] || { echo 'Set ACME_EMAIL or provision the existing ACME account first' >&2; exit 1; }
    account_args=(--account "$account")
fi

install -d -m 755 /etc/itzephir /usr/local/libexec/itzephir
install -m 755 timeweb-dns-hook.py /usr/local/libexec/itzephir/timeweb-dns-hook.next
mv -f /usr/local/libexec/itzephir/timeweb-dns-hook.next /usr/local/libexec/itzephir/timeweb-dns-hook
install -m 755 renew-project-certificate.sh /usr/local/libexec/itzephir/renew-project-certificate.next
mv -f /usr/local/libexec/itzephir/renew-project-certificate.next /usr/local/libexec/itzephir/renew-project-certificate
# Preserve the previous renewal credential if issuance or the staging renewal check fails.
previous_token=false
if [[ -f /etc/itzephir/timeweb-dns.token ]]; then
    install -m 600 /etc/itzephir/timeweb-dns.token /etc/itzephir/.timeweb-dns.token.previous
    previous_token=true
fi
restore_token() {
    result=$?
    trap - EXIT
    if [[ "$previous_token" == true ]]; then
        mv -f /etc/itzephir/.timeweb-dns.token.previous /etc/itzephir/timeweb-dns.token
    else
        rm -f /etc/itzephir/timeweb-dns.token
    fi
    rm -f /etc/itzephir/.timeweb-dns.token.next
    exit "$result"
}
trap restore_token EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
install -m 600 "$token_file" /etc/itzephir/.timeweb-dns.token.next
mv -f /etc/itzephir/.timeweb-dns.token.next /etc/itzephir/timeweb-dns.token
certbot certonly --non-interactive --manual --preferred-challenges dns \
    --server https://acme-v02.api.letsencrypt.org/directory \
    --manual-auth-hook '/usr/local/libexec/itzephir/timeweb-dns-hook auth' \
    --manual-cleanup-hook '/usr/local/libexec/itzephir/timeweb-dns-hook cleanup' \
    --deploy-hook /usr/local/libexec/itzephir/renew-project-certificate \
    --cert-name "itzephir-projects-$target" --domain "*.$domain" \
    --keep-until-expiring "${account_args[@]}"
certificate="/etc/letsencrypt/live/itzephir-projects-$target/fullchain.pem"
openssl x509 -in "$certificate" -noout -checkend 86400 >/dev/null
openssl x509 -in "$certificate" -noout -checkhost "routing-probe.$domain" | grep -q 'does match certificate'
# Exercise the saved renewal hooks against the staging CA; this does not replace the live cert.
certbot renew --cert-name "itzephir-projects-$target" --dry-run \
    --server https://acme-staging-v02.api.letsencrypt.org/directory
systemctl enable --now certbot.timer
trap - EXIT INT TERM
rm -f /etc/itzephir/.timeweb-dns.token.previous
echo "Wildcard certificate and automatic renewal verified for $target"
