#!/usr/bin/env bash
# Shared by VM provisioning and the real Docker DNS integration test.
PROJECT_DNS_IMAGE='coredns/coredns@sha256:7efd3c635b03efd68c4e8398fc45f0d993d0e9ab016f72c1cefb0fd6d01aa286'

start_project_dns() {
    local target="$1" container="$2" network="$3" dns_port="$4" directory="$5"
    # The official /coredns binary has a NET_BIND_SERVICE file capability. It must
    # remain in the bounding set for exec, even though this config listens on 1053.
    docker run -d --name "$container" --label "com.itzephir.project-dns=$target" \
        --network "$network" --restart unless-stopped --read-only --cap-drop ALL --cap-add NET_BIND_SERVICE \
        --security-opt no-new-privileges --user 65532:65532 --memory 64m --pids-limit 64 \
        -p "127.0.0.1:$dns_port:1053/udp" -p "127.0.0.1:$dns_port:1053/tcp" \
        -v "$directory:/config:ro" "$PROJECT_DNS_IMAGE" -conf /config/Corefile
}
