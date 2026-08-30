# Git workflow

- Make all changes and commits on a separate feature branch, using `codex/`
  as the branch prefix unless the user specifies another name.
- Do not commit or push directly to `main` or the legacy `dev` branch.
- Open a pull request targeting `main`; its CI deployment updates the shared
  preview at `https://dev.itzephir.com`.
- Leave merging to the user unless they explicitly request a merge. A merge
  into `main` deploys production at `https://itzephir.com`.
