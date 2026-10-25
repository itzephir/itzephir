#!/usr/bin/env bash
# Called by prepare-project-routing.sh after DNS-01 issuance and renewal verification.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root' >&2; exit 1; }
target="${1:?Usage: setup-project-routing.sh dev|prod}"
case "$target" in
    prod) domain=itzephir.com; dns_port=18553 ;;
    dev) domain=dev.itzephir.com; dns_port=18554 ;;
    *) echo 'Invalid environment' >&2; exit 1 ;;
esac
cd "$(dirname "$0")"
certificate="/etc/letsencrypt/live/itzephir-projects-$target/fullchain.pem"
test -s "$certificate"
test -s "/etc/letsencrypt/live/itzephir-projects-$target/privkey.pem"
openssl x509 -in "$certificate" -noout -checkend 86400 >/dev/null
openssl x509 -in "$certificate" -noout -checkhost "routing-probe.$domain" | grep -q 'does match certificate'
nginx -V 2>&1 | grep -q -- --with-http_auth_request_module
command -v dig >/dev/null
command -v docker >/dev/null

network="itzephir-projects-$target"
container="itzephir-project-dns-$target"
candidate="$container-next"
backup="$container-previous"
# CoreDNS v1.14.7, official multi-platform manifest. The website never accesses docker.sock.
image='coredns/coredns@sha256:7efd3c635b03efd68c4e8398fc45f0d993d0e9ab016f72c1cefb0fd6d01aa286'
docker pull "$image"
if docker network inspect "$network" >/dev/null 2>&1; then
    [[ "$(docker network inspect --format '{{index .Labels "com.itzephir.project-network"}}' "$network")" == "$target" ]] || {
        echo "Refusing to use an unmanaged project network: $network" >&2; exit 1;
    }
else
    docker network create --driver bridge --label "com.itzephir.project-network=$target" "$network" >/dev/null
fi
# Refuse existing foreign resources before touching their files or stopping anything.
for name in "$container" "$candidate"; do
    if docker inspect "$name" >/dev/null 2>&1; then
        [[ "$(docker inspect --format '{{index .Config.Labels "com.itzephir.project-dns"}}' "$name")" == "$target" ]] || {
            echo "Refusing to replace an unmanaged container: $name" >&2; exit 1;
        }
    fi
done
if docker inspect "$backup" >/dev/null 2>&1; then
    echo "Previous DNS replacement is incomplete: inspect $backup before retrying" >&2; exit 1
fi
directory="/etc/itzephir/project-dns-$target"
install -d -m 755 "$directory"
previous_corefile=false
if [[ -f "$directory/Corefile" ]]; then
    cp -p "$directory/Corefile" "$directory/Corefile.previous"
    previous_corefile=true
fi
previous=false
previous_renamed=false
promoted=false
restore() {
    result=$?
    trap - EXIT
    set +e
    docker rm -f "$candidate" >/dev/null 2>&1
    if [[ "$promoted" == true ]]; then docker rm -f "$container" >/dev/null 2>&1; fi
    if [[ "$previous_renamed" == true ]]; then docker rename "$backup" "$container"; fi
    if [[ "$previous_corefile" == true ]]; then
        mv -f "$directory/Corefile.previous" "$directory/Corefile"
    else
        rm -f "$directory/Corefile"
    fi
    if [[ "$previous" == true ]]; then docker start "$container" >/dev/null; fi
    exit "$result"
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cat > "$directory/Corefile.next" <<'COREDNS'
.:1053 {
    errors
    cache 5
    forward . 127.0.0.11
}
COREDNS
chmod 644 "$directory/Corefile.next"
mv "$directory/Corefile.next" "$directory/Corefile"
docker rm -f "$candidate" >/dev/null 2>&1 || true
if docker inspect "$container" >/dev/null 2>&1; then
    previous=true
    docker stop "$container" >/dev/null
fi
docker run -d --name "$candidate" --label "com.itzephir.project-dns=$target" \
    --network "$network" --restart unless-stopped --read-only --cap-drop ALL \
    --security-opt no-new-privileges --user 65532:65532 --memory 64m --pids-limit 64 \
    -p "127.0.0.1:$dns_port:1053/udp" -p "127.0.0.1:$dns_port:1053/tcp" \
    -v "$directory:/config:ro" "$image" -conf /config/Corefile >/dev/null
for transport in udp tcp; do
    ready=false
    extra=()
    if [[ "$transport" == tcp ]]; then extra=(+tcp); fi
    for attempt in {1..15}; do
        if dig +short +time=1 +tries=1 "${extra[@]}" @127.0.0.1 -p "$dns_port" "$candidate" A 2>/dev/null | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then ready=true; break; fi
        sleep 1
    done
    [[ "$ready" == true ]] || { echo "Project DNS $transport did not become ready" >&2; false; }
done
if [[ "$previous" == true ]]; then
    docker rename "$container" "$backup"
    previous_renamed=true
fi
docker rename "$candidate" "$container"
promoted=true
install -d -m 755 /usr/local/sbin
install -m 755 apply-nginx.py /usr/local/sbin/itzephir-apply-nginx.next
mv -f /usr/local/sbin/itzephir-apply-nginx.next /usr/local/sbin/itzephir-apply-nginx
if [[ "$previous_renamed" == true ]]; then docker rm "$backup" >/dev/null; fi
trap - EXIT INT TERM
rm -f "$directory/Corefile.previous"
echo "Project routing infrastructure prepared for $target. Normal CI activates nginx."
