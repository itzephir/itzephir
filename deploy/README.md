# Deployment

The website has two independent environments on one virtual machine:

| Git branch | Server path | Hostname |
| --- | --- | --- |
| `main` | `/srv/itzephir/prod/current` | `itzephir.com` |
| `dev` | `/srv/itzephir/dev/current` | `dev.itzephir.com` |

Every push is built and checked by `.github/workflows/build-website.yml`.
Pushes to `main` and `dev` are uploaded into a commit-specific release
directory and activated by atomically replacing the `current` symlink.

The GitHub repository requires these Actions secrets:

- `DEPLOY_HOST` — SSH host or IP;
- `DEPLOY_USER` — restricted deployment user;
- `DEPLOY_SSH_KEY` — private key used only by GitHub Actions;
- `DEPLOY_KNOWN_HOSTS` — pinned SSH host key line.

The nginx configuration is stored at `deploy/nginx/itzephir.com.conf` and is
installed as `/etc/nginx/sites-available/itzephir.com`.

## DNS

The required records are:

```text
@    A      deployment-host.example.invalid
dev  A      deployment-host.example.invalid
www  CNAME  itzephir.com.
```

TLS certificates must be requested only after all records resolve to the new
server.
