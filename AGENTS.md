# Git workflow

- Make all changes and commits on a separate feature branch, using `codex/`
  as the branch prefix unless the user specifies another name.
- Do not commit or push directly to `main` or the legacy `dev` branch.
- Open a pull request targeting `main`; its CI deployment updates the shared
  preview at `https://dev.itzephir.com`.
- Leave merging to the user unless they explicitly request a merge. A merge
  into `main` deploys production at `https://itzephir.com`.

# Figma workflow

- Use [silships/figma-cli](https://github.com/silships/figma-cli) as the primary
  tool for Figma reads, design edits, components, variables, asset exports, and
  visual verification. Run it with `figma-cli` from the project root.
- Follow [website/FIGMA.md](website/FIGMA.md) for installation, connection,
  commands, and troubleshooting. Retry local connection commands outside the
  sandbox before concluding that Figma is unavailable.
- Before working, list open files with `figma-cli files`, confirm the project's
  file, and set `FIGMA_FILE` to its title for subsequent commands. Use
  [website/DESIGN.md](website/DESIGN.md) for the approved baseline and node IDs.
- Reuse existing components, styles, and variables. Create visual nodes with
  `render` / `render-batch`; use `eval` for reads or Plugin API operations without
  an equivalent CLI command. Preserve existing nodes unless the user requests
  their removal.
- After design changes, run `figma-cli verify "<node-id>" --measure` and inspect
  the saved screenshot before reporting completion. Export assets with the CLI
  and keep the website implementation aligned with the approved design.
- Use Figma MCP or UI automation only when the CLI cannot perform the required
  operation or its connection remains unavailable; explain the fallback.
- Keep `website/DESIGN.md` as the curated implementation reference. Write CLI
  extracts to a separate file instead of replacing it.
