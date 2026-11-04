# ADR 001: one shared site shell

Status: accepted. Contract version: **2**.

The shell is an architectural boundary, not a page decoration. A route must not
redefine header/footer markup, dimensions, control order or navigation behavior.
This applies to current and future site sections, including `/dev`.

## Ownership and permitted inputs

`SiteShell.kt` owns the root `#portfolio`, skip link, app header, editor tabs and
`main#content`, followed by the shared `footer.site-footer`. A page supplies only
its typed `PortfolioPage`, theme, visited-tab state, neighbor names and a
`MAIN.() -> Unit` content block. There are no callbacks
or flags for replacing the header, hiding controls per route, or injecting shell
classes. Neither main-content blocks nor the locator renderer supply a footer.
`site-shell.css` owns shell selectors; content styles stay in `styles.css`.
The root has no page-specific class. Shared theme tokens and icon utilities are
allowed dependencies, covered by the same browser contract.

For a fixed viewport and theme, switching routes may change **only**:

- The path beside the wordmark: `/`, `/projects`, `/about`, or a new registered
  route. It occupies a fixed-width slot and cannot move adjacent controls.
- The visited-tab list and selected tab.
- The main document and page metadata.

Theme and neighbor names are shared data, not page-specific layout options.

Version 2 moves the previously separate portfolio and public `/dev` footers into
the shell. The `/dev` home label was a non-interactive span, and authenticated
panel documents omitted the footer. One renderer now supplies the same four
working links on every document, including HTMX responses and panel errors.
The same revision removes the decorative RU label from the shared desktop app
bar, matching the approved minimal welcome design. The webring, theme and GitHub
controls keep their dimensions and order on every route.

## Invariants

- The desktop app bar is 60 px high. Wordmark, webring, theme and GitHub
  keep their order, alignment and dimensions. The wordmark slot is 89.36 x 32 px;
  buttons are 44 x 44 px; the ring is 344 x 36 px. Horizontal padding is 32 px
  from 1000 px, and 24 px at 720–999 px, where the path is hidden. There is no
  decorative language label.
  Paths longer than the fixed 11ch slot truncate to one line with an ellipsis;
  the full path remains the registered document URL. Many visited tabs scroll
  horizontally inside the unchanged 45 px strip rather than widening the page.
- Below 720 px, the 69 px header contains only the webring with 24 px horizontal
  padding and 44 px link targets. Theme and Back remain in content; desktop tabs
  and branding are hidden. These rules apply to every route.
- Preserve a stable scrollbar gutter and never introduce horizontal overflow at
  320 px. Content length, tab count and theme must not shift the app bar.
- The ring renders three named, usable HTML links in the first screen without
  JavaScript. Registered prev/index/next URLs remain independent of neighbors'
  display names; unavailable upstream data cannot block or remove the header.
  Names come from `/itzephir/data`, with last-successful-response caching and
  direction labels before the first response, never hardcoded member names.
- Tabs open only when a page is visited, preserve order, never duplicate and have
  working close controls. Closing an inactive tab keeps the current page;
  closing the active one selects its previous/next neighbor. The final content
  tab returns to welcome; a lone welcome tab has no close button.
- Full documents, HTMX theme fragments and history restoration use the same
  shell. Theme changes preserve route and tabs. All navigation works without JS.
- Every shell has exactly one footer after `main#content`, with a top divider:
  `~/itzephir` links to `/` on the left; Telegram, GitHub and Email appear on the
  right, in that order. No route adds a slogan, owner name or alternate footer.
  Telegram/GitHub open a new tab with `noopener noreferrer`; Email uses `mailto:`.
  The footer stays in normal document flow after long content, at the bottom of
  short pages. It is centered, at most 900 px wide, with 32 px side insets on
  desktop and 24 px below 720 px. All four links have 44 px targets and visible
  keyboard focus. Contact gaps are 24/16/12 px at desktop/mobile/below 360 px;
  the bottom inset is 32 px on desktop and 24 px on mobile.
  A visible staging badge reserves 84/80 px plus the bottom safe area instead,
  keeping all footer links clear of the fixed overlay. This is shared environment
  state, not a route-specific layout.

## Executable contract

`SiteShellContractTest` checks every registered `PortfolioPage`, both themes,
document/fragment/history responses and empty/populated tab state against the
approved HTML fixtures in `src/test/resources/contracts/site-header.html` and
`site-footer.html`. The only header-fixture change in version 2 is removal of
the RU span; links, control order and HTMX behavior remain protected.
Only route, theme, asset digests and neighbor names are normalized. Control order,
links, semantics and HTMX behavior remain frozen by the fixture.

`deploy/tests/site-shell-contract.test.mjs` prevents shell selectors and markup
from gaining another owner. `portfolio.test.mjs` verifies approved dimensions,
route labels and geometry across all registered routes at
320/390/719/720/768/999/1000/1440 px, both themes, plus opening/closing tabs,
theme changes, Back/reload and JavaScript-off navigation. Footer checks cover
identical links, relative geometry, touch targets, no overflow, and the home
transition from public `/dev` without JavaScript. These tests run in the
existing CI build; deployment depends on that build succeeding.

Ordinary page content changes must pass this contract without editing the shell
or its fixtures. Do not update expectations merely to make a regression pass.
A deliberate contract change is a separate architectural change: explain the
new behavior and rationale, update this ADR, the tests and the corresponding
Figma masters together, and review both themes and mobile/desktop states before
merging. This makes redesign explicit rather than an accidental page override.
