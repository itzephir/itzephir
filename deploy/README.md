# Deployment

The website has two independent environments on one virtual machine:

| Trigger | Server path | Hostname |
| --- | --- | --- |
| Push/merge into `main` | `/srv/itzephir/prod/current` | `itzephir.com` |
| Open/update/reopen a same-repository PR targeting `main` | `/srv/itzephir/dev/current` | `dev.itzephir.com` |

Develop changes in a separate branch and open a PR targeting `main`. The
`.github/workflows/build-website.yml` workflow checks and builds the PR merge
commit, then deploys it to development. It publishes the preview URL in the
GitHub environment and updates a single comment in the PR. Merging the PR into
`main` triggers production deployment; opening a PR never changes production.

Development is one shared preview, not one instance per PR: the last completed
deployment wins. Uploads and activation are serialized per environment, and a
running deployment is never canceled by another deployment. Each run gets its
own release directory, activated by atomically replacing the `current` symlink.
Closing a PR leaves the last preview available until another PR deploys.

Fork and Dependabot PRs run build checks only and never receive deployment
secrets. The workflow intentionally uses `pull_request`, not
`pull_request_target`. Manual workflow runs are build-only. Direct pushes to
the legacy `dev` branch no longer trigger deployment.

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

## HTTPS

One Let's Encrypt certificate covers `itzephir.com`, `www.itzephir.com`, and
`dev.itzephir.com`. Certbot renews it automatically with the webroot challenge
stored in `/var/www/letsencrypt`; nginx keeps that challenge path available over
HTTP and redirects all other requests to HTTPS. `www.itzephir.com` redirects to
the canonical `itzephir.com` hostname.

The production artifact is prepared by `deploy/prepare-web-release.sh`. It
injects preload hints for generated Wasm filenames, removes source maps, and
creates Brotli and gzip variants. nginx serves these precompressed assets and
keeps the hashed Wasm files immutable while the unhashed entry script is never
cached across releases.
