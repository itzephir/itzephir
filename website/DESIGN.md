# Portfolio baseline

Source: [01 · Baseline in Figma](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=14-3481).

## Staging badge v1

The [review sheet](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=101-6652)
shows instances of component set `101:6651`, `Environment / Staging badge`, in
Dark/Light × Default/Hover variants. The 252 × 52 px overlay uses Workspace
panel/text/line/muted/raised variables, IBM Plex Sans 13/20, 12 px corners, a
subtle shadow and a 6 px amber status marker. Status tokens `badge-status`
are `#e1b96a` in dark and `#926619` in light. The cross has a 44 × 44 px target
and a visible keyboard focus outline.

Placement is fixed at bottom right: 16 px desktop insets, 12 px below 720 px,
respecting safe areas. The same component fits a 320 px viewport without
shrinking its controls. It sits outside the shared site shell and does not
change the header contract. `staging-badge.css` owns its isolated selectors.
The marker is paired with the explicit “Вы на тестовом стенде” label; the badge
is hidden in print. See [runtime behavior](README.md#staging-badge) for query
parameters, close behavior and environment isolation.

## Portfolio screens

| Screen | Dark | Light |
| --- | --- | --- |
| Welcome desktop | `14:1898` | `14:2009` |
| Projects desktop | `14:2734` | `14:2882` |
| Welcome mobile | `14:3029` | `14:3254` |
| Projects mobile | `14:3123` | `14:3346` |

The approved full logo is the connected `itz` monogram followed by `ephir` in
Quicksand Bold, with optical letter spacing fixed in Figma. Source components
are `22:4698` (light ink, dark surfaces) and `22:4699` (dark ink, light surfaces),
in `Brand / Wordmark`. The [brand sheet](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=22-4701)
records the approved composition. Use the complete `wordmark.svg` exports;
the letters are outlines, so no Quicksand font download is needed at runtime.
The 223.3946 × 80 px component is exported on a 224 × 80 px SVG canvas and
scaled by 0.4 into an 89.36 × 32 px desktop slot, matching Figma.

The short connected `itz` mark retains uniform 2 px corners on the 64 px master.
Source variants `22:4601` and `22:4602` remain the SVG favicon sources. Native
16/32 px favicon PNG exports (`22:5040`, `22:5050`, `22:5058`, `22:5068`) are
unchanged. Use the short mark for icons, and the whole-name wordmark in desktop
headers.

Colors and content spacing are defined in `styles.css`; shared shell geometry
is owned by `site-shell.css`. Icons and portrait are local
exports from the same Figma screens. IBM Plex Sans/Mono include Cyrillic and are
self-hosted; font and icon license notices are under `third_party`.

The implementation uses fluid widths and a 720 px breakpoint. The mobile header
contains the webring only; routes are reached through content links and Back.
Within each breakpoint, header dimensions, alignment and control sizes are shared
by all routes and both themes; page-specific header geometry is not allowed.
Only the path beside the wordmark changes: `/`, `/projects` or `/about`, in a
fixed-width slot.
The [site shell contract v1](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=59-2692)
records this architectural boundary with dark/light route examples and mobile
states. The desktop app bar uses component set `59:1689` (masters `59:1687` /
`59:1688`), with only Theme and Path as inputs. Mobile uses set `40:7383`
(masters `40:7381` / `40:7382`). All eight baseline screens use these instances;
the Dev header family `40:7356` composes the same desktop app bar with tabs.
See [ADR 001](architecture/site-shell.md) for code ownership and CI enforcement.
Desktop tabs represent visited pages, preserve their opening order in the
session, and provide separate close controls. See `README.md` for tab behavior.
The profile route adapts the existing biography to the same style because the
baseline includes a profile shortcut but no separate profile screen.

Browser verification captures both baseline pages in both themes and checks
320/390/719/720/768/999/1000/1440 px layouts, identical header geometry across
welcome/projects/about and both themes, loaded assets, logo size, visible named ring
links, 44 px mobile touch targets, theme persistence, and navigation without JS.
Screenshots are generated under `website/build/browser-check` and uploaded as
the `portfolio-screenshots` CI artifact.

## Development workspace UI v1

The [implementation frames](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=70-6622)
on `02 · Dev / Витрина и панель` supersede the exploratory gallery/admin
directions for implementation. They compose the accepted shell v1 masters,
keep IBM Plex Sans/Mono and Workspace color variables, and use 24 px card
padding, 10 px card corners, 44 px form controls and 40/32 px desktop/mobile
page headings. The desktop management sidebar is 232 px wide; on mobile it
becomes content navigation below the shared webring. Back and theme controls
remain in the document, outside the mobile header.

| View | Dark | Light | Mobile |
| --- | --- | --- | --- |
| Public `/dev` | `70:2982` | `70:3158` | `70:3310` |
| Admin projects | `70:3373` | `70:3555` | `70:3733` |
| Owner project settings | `70:3820` | `70:3971` | `70:4118` |
| Creation / owner assignment | `70:4174` | `70:4338` | `70:4498` |
| Request review | `70:4567` | `70:4720` | `70:4869` |
| Users | `70:4927` | `70:5085` | `70:5239` |
| Account | `70:5302` | `70:5448` | `70:5590` |
| Sign in | `70:5641` | `70:5771` | `70:5897` |
| One-time service token | `70:5934` | `70:6079` | `70:6212` |
| Empty / stale data states | `70:6254` | `70:6387` | `70:6516` |

The gallery uses real registry names and slugs. Approved, non-revoked projects
appear on the gallery; projects with `development` readiness link to
`/dev/project/<slug>/` on the current host. Owner assignment appears
only for administrators in existing project settings. GitHub login/linking is
conditional on provider configuration; password login is always the initial
configured flow. Sample projects, usernames and masked tokens in Figma are
illustrative; no credentials or actual service tokens are design assets.

The portal from welcome to `/dev` is a content card, not a permanent editor
tab. Dev documents join the same visited-tab cookie; project settings accept
only registered UUID paths. The strip scrolls when many documents are open,
preserving the app bar and 45 px strip height. The approved header HTML fixture
and app bar geometry are unchanged.

`LocatorPageRenderer` separates locator forms and permission checks from the
website's document renderer. `Development.kt` supplies its content to
`SiteShell.kt`; `dev.css` owns only section content, and all global chrome
continues to be owned by `site-shell.css`. The legacy `/dev/assets/style.css`
URL serves the same content stylesheet for deployment compatibility.

### Catalog description and planning state

The editable [catalog supplement](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=123-3763)
preserves the v1 frames and shared shell. Gallery examples are `123:3763` (dark),
`123:3886` (light) and `123:4007` (mobile). They reuse the existing cards,
Workspace typography and theme bindings. The itzmoney text is a design example;
the implementation reads project metadata from PostgreSQL.

Cards optionally show a plain-text description before the stable entry path.
Readiness `planning` shows «Проектирование» without a launch button;
`development` retains «В разработке» and the existing launch button. The gallery
counter says «ПРОЕКТЫ» because a visible card does not mean a deployed application.
Review approval is labelled «Одобрен» separately from readiness.

Creation and settings reuse the existing form fields with a description textarea
(500 characters, at least 112 px high) and a readiness select (44 px). Labels,
focus states, colors and typography follow the existing Dev/Input treatment.
No shared header, navigation, component master or shell contract changes.

Figma CLI returned no open design files, including outside the sandbox after
opening the portfolio. The supplement was added and inspected through Figma MCP
as the documented fallback; editable layers, IBM Plex Sans/Mono, theme bindings,
dimensions and screenshots were checked. Browser screenshots cover both themes
at 320 and 1440 px; the existing wider responsive suite also covers the forms.
