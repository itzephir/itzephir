# itzephir in OpenDesign

This package transfers the approved itzephir design into a private OpenDesign
workspace. It contains editable HTML/CSS pages, a reusable design-system package,
and SVG references exported from the approved Figma frames. SVG references retain
the appearance of those frames; they do not reproduce Figma's component graph.

The portfolio application remains Ktor. OpenDesign previews are design documents;
they do not run the registry, authentication, administration or publishing backend.
The portfolio pages reuse the current rendered documents; the Dev specimens
compose the existing LocatorPanel markup patterns and CSS with synthetic data.
All screen families have editable HTML/CSS in both themes. Figma SVGs are extra
references, rather than substitutes for those pages. The baseline is
[website/DESIGN.md](../../website/DESIGN.md).

## Build

1. Build the current site with `./gradlew :website:installDist` and run it locally
   on a free loopback port with `DEPLOYMENT_ENV=prod`.
2. Follow [the Figma connection guide](../../website/FIGMA.md), select the
   `itzephir — programmer theme sketches` file, and run
   `python3 design/opendesign/export-figma.py --output "$EXPORT_DIR"`.
   This reads Workspace variables with their original alias targets and exports
   each approved frame in `screens.json` into `$EXPORT_DIR/references`.
3. Keep that export for visual comparison and provenance.
4. Run:

   ```sh
   python3 design/opendesign/build.py \
     --base-url http://127.0.0.1:4188 \
     --variables "$EXPORT_DIR/figma-workspace.json" \
     --references "$EXPORT_DIR/references" \
     --output design/opendesign/build
   python3 design/opendesign/verify.py design/opendesign/build
   ```

The output contains `project/` and `design-system/`. Preview `project/index.html`
and `design-system/components.html` with a static HTTP server. Internal page links,
theme switching, fonts and imagery use bundled relative paths. Closing a document
tab is preview-only; server forms and HTMX requests are not carried into previews.

## Import

Transfer only these two allowlisted output directories to the private workspace.
Never transfer `.git`, environment files, local CLI configuration, databases or
build caches. The Mac `opendesign` command executes on the server, so paths passed
to its import commands refer to the container, not to the Mac.

Register `design-system/DESIGN.md` through `POST /api/design-systems` with
`artifactMode: "agent-managed"`. Install the package's remaining files beside
that newly created system's `DESIGN.md`, preserving its server-owned metadata.
Then `POST /api/design-systems/:id/workspace` creates the editable library project.
The package uses the official `od-design-system-project/v1` manifest format.
`status: "published"` makes it selectable in the local library; it does not
publish it to OpenDesign Cloud.

Import `project/` with `opendesign project import <container-directory>` and
`--design-system <created-system-id>`. This registers an external folder in
OpenDesign 0.27; it does not copy it into managed storage. Next use
`opendesign project duplicate <imported-project-id> --name itzephir` to create
the permanent managed copy. Verify its checksums and that `project info` has no
`metadata.baseDir`, then remove the temporary import record and upload directory.
Keep the original package until verification finishes. Check the result in the
browser and compare its assets and both themes with the Figma references.
