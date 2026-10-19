# Working with Figma

[silships/figma-cli](https://github.com/silships/figma-cli) is this project's
primary Figma tool. Use it to inspect and edit designs, reuse components, manage
variables, export assets, and verify the result.

The source file is
[the portfolio design](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=14-3481).
Approved screens, branding, and node IDs are recorded in [DESIGN.md](DESIGN.md).

## Install and connect

The CLI requires Node.js 18+ and Figma Desktop. Its npm package is named
`figma-ds-cli`; the executable is `figma-cli`.

```bash
# Check an existing installation first.
figma-cli --version

# Install if missing.
npm install --global figma-ds-cli
```

Open the portfolio design in Figma Desktop. From the repository root, connect
and check the available files:

```bash
figma-cli connect
figma-cli files
```

The default connection patches and restarts Figma Desktop to enable local
remote debugging. `figma-cli connect --browser` offers a separate Chromium
profile instead; open the portfolio file there after signing in. See the
[upstream connection guide](https://github.com/silships/figma-cli#how-it-connects-to-figma-yolo-browser-or-safe-mode)
for the available modes.

Set `FIGMA_FILE` to the portfolio's title from `files`, particularly when several
design files are open. This selects by title, not by the URL's file key. Confirm
the selected file before making changes:

```bash
export FIGMA_FILE="<portfolio file title from figma-cli files>"
figma-cli eval 'return {name: figma.root.name, fileKey: figma.fileKey}'
```

Check that the name belongs to the portfolio and, when `fileKey` is available,
that it matches `zHX90tHJtN7q1PqavIO68s`.

## Inspect, edit, and verify

These examples run from the repository root after selecting the source file:

```bash
# Read an approved desktop screen and the available design variables.
figma-cli node tree "14:1898" --depth 3
figma-cli var list

# Inspect dimensions and save a screenshot for visual review.
figma-cli verify "14:1898" --measure

# Export an approved logo as SVG.
figma-cli export node "22:4601" --format svg --output /tmp/itzephir-logo.svg

# Extract to a separate file; DESIGN.md is maintained by hand.
figma-cli extract /tmp/itzephir-figma-extract.md
```

Use `render` / `render-batch` for new frames and components, and instances for
existing components. Bind to the design's variables and styles; select the
intended collection with `--collection` when needed. Use `eval` for focused reads
or Plugin API operations without a dedicated CLI command. Load pages with
`await figma.loadAllPagesAsync()` before reading nodes outside the current page,
and use async node and variable APIs.

After editing, run `verify "<node-id>" --measure`, review the saved PNG and
dimensions, and correct any layout issues. Export approved assets into the
website's existing asset locations and update [DESIGN.md](DESIGN.md) when the
approved baseline changes. Keep temporary screenshots and extracts outside the
repository. Use `figma-cli <command> --help` for additional command options.

## Connection troubleshooting

```bash
figma-cli diagnose
figma-cli daemon status
```

A running daemon alone does not prove the connection works: `files` and a
read-only `eval` must succeed too. If the remote debugging port is closed or a
Figma update reset the connection, run `connect` again. If diagnosis reports a
daemon authentication mismatch, run `figma-cli daemon restart`.

If `connect` reports that macOS cannot configure Figma, enable the terminal
application under System Settings → Privacy & Security → App Management,
restart that terminal application, and reconnect. Alternatively, use
`connect --browser` as described above.

Local connections may be blocked by an agent's sandbox. Retry outside it before
diagnosing an application failure. If the CLI remains unavailable or lacks the
required operation, explain the limitation and use Figma MCP or UI automation
for that operation.

Figma access is a local design workflow. Website builds and CI do not require a
running Figma session; validate implementation changes as described in
[README.md](README.md#build-and-verify).
