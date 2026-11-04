# Private OpenDesign workspace

This is the first step of moving the itzephir design workflow to
[OpenDesign](https://github.com/nexu-io/open-design). The existing Figma design and
website are preserved; importing their design system is a separate next step.

## Placement and capacity

The application host has capacity for the initial workspace; operator inventory is kept private.

The runtime is capped at 384 MiB RAM, a 192 MiB Node heap, 0.75 CPU and 256
processes. No application build runs on the VM. Large exports and server-side
agent execution should be measured before increasing these limits or moving the
workspace to a separate VM. The official image does not bundle coding-agent CLIs.
External agents can run on the Mac and use the workspace without model API access
from this VM. A model/provider is needed only if server-side generation is selected.

`opendesign.itzephir.com` already resolves to the VM via the existing wildcard A
record. The existing `itzephir-projects-prod` wildcard certificate covers it and
retains its existing Certbot renewal. No DNS mutation or new certificate is
needed. An exact nginx hostname takes precedence over the registry wildcard;
this private tool does not need a public project-registry entry. The registry
reserves `opendesign` so public projects cannot claim this infrastructure hostname.

## Delivery and access

Open a PR targeting `main`. **OpenDesign hosting** checks credentials, rollback,
the real TLS proxy, and the pinned official Docker image. Merging deploys through
the existing `infrastructure` GitHub environment, whose branch restriction remains
`main`. Manual dispatch from `main` retries the same committed configuration.
It reuses `INFRA_SSH_KEY`, `DEPLOY_HOST` and `DEPLOY_KNOWN_HOSTS`; no additional
GitHub secret is needed. Ordinary portfolio deployments do not modify this site.

Open <https://opendesign.itzephir.com>. The browser's username is `open-design`.
As the VM owner, retrieve the generated password in your own terminal:

```sh
ssh root@deployment-host.example.invalid 'sed -n "s/^OD_API_TOKEN=//p" /etc/itzephir/opendesign.env'
```

The token is generated on the VM, preserved on retries, and never printed by CI.
The environment file is root-only; nginx receives only its password hash.
HTTPS Basic authentication covers all paths, including HTML, static assets,
artifacts and API requests. The daemon additionally retains its token check and
allows exactly `https://opendesign.itzephir.com` as a remote browser origin.
Its host port is bound strictly to `127.0.0.1:7456`. No Docker socket, host home,
repository checkout or host-installed agent credential is mounted in the runtime.

## CLI on the Mac

`mac-cli.py` is installed as `~/.local/bin/opendesign`. It invokes the official
CLI inside the running workspace over the existing SSH connection, with the
container's unprivileged user and loopback API. No OpenDesign desktop application,
local Node version change, model credential copy or second daemon is required.
The system `/usr/bin/od` remains available. Its local configuration is
`~/.config/opendesign/remote.json`; it contains only the SSH destination, container
name and loopback URL, not a password or API token.

```sh
opendesign --help
opendesign status --json
opendesign project list --json
opendesign design-systems --help
opendesign mcp
```

`opendesign mcp` provides clean stdio for a local MCP client over SSH. The model
continues to run through the local coding agent. Upstream commands that explicitly
start a run or generate media still execute on the server and require a configured
server-side provider/runtime; invoking the bridge itself does not start a run.
Paths passed to this remote CLI refer to the container; upload local files before
using an upstream command that reads a path. Agent forwarding and interactive SSH
password prompts are disabled. A missing/stopped container exits with a clear
message and does not attempt to start or provision it.

## Persistence, updates and recovery

Use the upstream [daemon data directory contract](https://github.com/nexu-io/open-design/blob/open-design-v0.27.0/AGENTS.md#daemon-data-directory-contract)
as the authority for managed data paths. Compose uses one explicit `OD_DATA_DIR`
and the dedicated external volume `itzephir-opendesign-data`. Removing or
recreating the container does not remove that volume. Never run a volume-pruning
command as part of an update.

The deployment entry point is `deploy.py`. It rejects foreign volumes and
unmanaged configurations, checks the existing certificate/nginx configuration,
pulls the pinned image before stopping the workspace, and serializes deployments.
nginx changes share `/run/lock/itzephir-nginx.lock` with the portfolio publisher.
Streaming responses, uploads and WebSocket upgrades pass through the proxy.

Update the explicit image tag **and digest** in `compose.yaml` in a new PR.
Before replacing an existing workspace, deployment stops it and makes an offline
data archive under `/srv/itzephir/opendesign/backups/`. Seven successful update
snapshots are retained with private directory permissions. These archives contain
workspace secrets as well as projects; keep them private. They complement the
VM's existing disk backup setting and are not off-machine backups.

Failed startup or authenticated HTTPS checks restore the preceding nginx and
Compose configuration, restore the pre-update data snapshot if the candidate ran,
and restart the preceding image. A failed initial deployment leaves no enabled
nginx site and retains its volume/credentials for retry. Each successful deployment
records its source revision/image in `/srv/itzephir/opendesign/deployment.json`.

Inspect status without printing secrets:

```sh
ssh root@deployment-host.example.invalid 'docker ps --filter name=itzephir-opendesign; docker stats --no-stream itzephir-opendesign'
```

The website's services, PostgreSQL and other project containers are not restarted.
