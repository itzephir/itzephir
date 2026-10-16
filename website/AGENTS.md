# Website changes

Read `README.md` for runtime and navigation behavior and `DESIGN.md` for the
approved visual baseline. Keep `DESIGN.md` curated: record approved changes
instead of replacing it with generated Figma data. Root Git workflow rules apply.
The accepted [site shell contract](architecture/site-shell.md) is an architectural
requirement, enforced in CI. Do not weaken its tests or regenerate its fixture to
accept an incidental page change. Deliberate changes require an explicit
architectural change with the ADR, Figma masters and verification updated together.

## Rendering and assets

- Keep Ktor + kotlinx.html server rendering and the existing HTMX enhancement.
  Navigation, theme switching, tabs and webring must work without JavaScript.
- `src/main/kotlin/com/itzephir/website/SiteShell.kt` alone owns shared chrome;
  `src/main/resources/static/site-shell.css` alone owns its selectors. Pages in
  `Portfolio.kt` supply main content, not shell markup or layout overrides.
- Use the existing theme variables and self-hosted fonts/assets. Reference static
  resources through `asset(...)` to preserve cache invalidation.
- Check changes in both dark and light themes. Theme switching must preserve the
  current route and open tabs; keep full-document history restoration working.

## Shared header

- The header is one shared layout for the entire site. At the same viewport,
  opening, switching or closing a document must not move or resize the logo,
  webring, language label, theme button or GitHub button.
- Only the path beside the wordmark changes with the current page:
  `/` on welcome, `/projects` on projects, `/about` on the profile. Derive it from
  `PortfolioPage.path`; do not restore `/workspace` or add a `/workspace` route.
- Keep the path in its fixed-width slot so different text lengths do not change
  header geometry. Keep the stable scrollbar gutter for short and long pages.
- Do not add page-specific header CSS, including different control sizes on
  welcome. Responsive rules must apply to all pages equally.
- Current header height is 60 px at widths >= 720 px and 69 px below 720 px.
  Desktop icon buttons have 44 x 44 px targets. At 720–999 px the path and
  language label are hidden. Preserve these shared breakpoints when editing pages.
- Below 720 px the header contains only the webring. Do not add a mobile brand,
  tab strip or page menu. Theme switching belongs beside the greeting or Back
  control; page navigation uses content links and Back.

## Branding and webring

- Use the complete approved `design/{dark,light}/wordmark.svg` in the desktop
  header: connected `itz` + `ephir` in Quicksand Bold, with outlined lettering.
  Preserve its spacing and aspect ratio; do not rebuild it with live text.
- Use the short connected `itz` mark for favicons. Keep the light/dark favicon
  assets and their browser color-scheme selection. Do not use an isolated `Z`/`z`.
- The webring is part of the shared header and must be visible in the first
  viewport, including at 320 px. Show neighbor names, both directions and the
  ring index; mobile link targets must be at least 44 px high.
- Keep ordinary HTML links to
  `https://webring.otomir23.me/itzephir/prev`,
  `https://webring.otomir23.me/`, and
  `https://webring.otomir23.me/itzephir/next`. Do not replace them with direct
  neighbor URLs or JavaScript-only navigation. Neighbor lookup must not delay
  rendering or hide the widget on upstream failure.
- Get neighbor names from `/itzephir/data`; never hardcode members' names. Retain
  the last successful response during an outage and use direction labels only
  before the first successful response.

## Pages and editor tabs

- Welcome lives at `/` and is named `welcome.md` in the tab strip. Projects use
  `/projects`; the profile uses `/about` and the tab label `about.md`.
- Tabs represent visited pages, in opening order. Reopening a page must not
  duplicate its tab. Preserve tabs across navigation, reloads and theme changes.
- Keep close controls functional and separate from tab links. Closing an
  inactive tab keeps the current page; closing the active tab selects its
  previous neighbor, or the next if needed. The final content tab returns to
  welcome; a lone welcome tab has no close button.
- Match the approved editor style and existing page components. A page-content
  change must not silently redesign the shared shell or mobile navigation.

## Verification

- For code or style changes, run from the repository root:
  `./gradlew check :website:installDist`, then
  `CHROME_EXECUTABLE="/path/to/Chrome" npm test --prefix deploy`.
  Install browser-test dependencies with `npm ci --prefix deploy --ignore-scripts`
  if needed. Documentation-only changes do not require rebuilding the site.
- Reuse `deploy/tests/portfolio.test.mjs` for browser regressions. Header changes
  must preserve identical geometry on `/`, `/projects` and `/about`, both themes,
  and 320/390/719/720/768/999/1000/1440 px widths. Check route labels as well as
  opening/closing tabs, theme switching and JavaScript-off navigation.
- Inspect relevant screenshots under `website/build/browser-check`. When a PR
  updates the shared preview, verify the changed behavior on
  `https://dev.itzephir.com` after CI deployment. Report local checks, CI, preview
  and production status separately; a passing local build is not a deployment.
