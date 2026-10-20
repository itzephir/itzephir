# itzephir.com website

The portfolio is a Kotlin/JVM application using Ktor 3.6, kotlinx.html, and HTMX.
Ktor renders the complete page on the server. There is no Compose canvas, Wasm
bundle, hydration, or frontend build toolchain.

## Run locally

Use JDK 21 or newer:

```bash
./gradlew :website:run
```

Open http://127.0.0.1:8080. Optional environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP listening port |
| `BIND_HOST` | `127.0.0.1` | Listening address |
| `COOKIE_SECURE` | `false` | Use `true` behind HTTPS in deployment |
| `DEPLOYMENT_ENV` | production behavior | Set `dev` to test the staging badge locally; the release target takes precedence |

## Staging badge

`StagingBadge.kt` is an application-wide Ktor response interceptor, installed only
for stage. It adds a fixed bottom-right “Вы на тестовом стенде” badge to complete
dynamic HTML documents, including the locator UI and proxied project pages under
`/dev/project/<slug>/`. APIs, assets, downloads,
encoded/validated bodies and HTMX fragments pass through unchanged. Full HTMX
history restoration receives one badge. The badge is outside `#portfolio`, so
theme swaps do not duplicate it or alter shared header geometry.

- No `show_badge` query parameter, or `show_badge=1`: show the badge.
- `show_badge=0`: omit the badge and its CSS/JavaScript references.
- Production: ignore the query parameter; the interceptor is not installed.

The close control sets `show_badge=0` in the current URL while preserving other
parameters, the current document and HTMX history state. With JavaScript disabled
it follows a normal server-rendered link. Hiding applies to that URL; a new URL
without the parameter shows the badge again. Both site themes, a 44 px close
target, keyboard focus, mobile safe areas and print hiding are supported.

The committed activation script writes `dev` or `prod` to the candidate release's
`deploy/environment` before starting it. The application trusts this local file,
not the request hostname or query. Existing VMs need no manual systemd changes.
An absent marker defaults to production behavior; `DEPLOYMENT_ENV=dev` is the
local development override. See the [Figma component](DESIGN.md#staging-badge-v1).

The stage public nginx sends ordinary `/dev/project/` requests to the internal
`/__staging-project/` location. `StagingProjectGateway.kt` forwards their original
URI to the existing, fixed loopback gateway on port 18101, and the same badge
plugin processes its HTML response. Direct public access to the internal
location returns 404. Production has neither this nginx rewrite nor this Ktor
endpoint. Upgrade requests retain the existing nginx gateway path.

The proxy requests identity encoding from projects, streams non-HTML responses
and request bodies, and preserves statuses, cookies and redirects. For HTML
being decorated it removes upstream cache validators and uses `no-store`, so an
old cached project document cannot hide the badge. `show_badge=0` retains the
upstream cache policy and conditional responses. Partial/download responses
are passed through. Absolute redirects containing nginx's private listener port
are converted to the corresponding public path. Badge styles are isolated from
project styles and include their own self-hosted font; projects without a
`data-theme` use the browser's preferred color scheme.

## Rendering and theme

`src/main/kotlin/com/itzephir/website/Portfolio.kt` renders the welcome page (`/`),
projects (`/projects`), and profile (`/about`). Static CSS, images, bundled IBM
Plex Sans/Mono fonts, and HTMX live in
`src/main/resources/static`. Resource URLs contain a digest to invalidate browser
caches after updates; assets are cached for an hour.

The implementation follows the approved [Figma baseline](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=14-3481):
dark/light colors, desktop editor tabs, mobile content navigation, and the
whole-name logo: connected `itz` + `ephir` in Quicksand Bold. The complete SVG
exports retain the outlined letters and optical spacing, and render in a
32 px-high desktop slot. The short `itz` SVG and transparent 16/32 px favicon
exports remain unchanged. Favicon variants follow browser color scheme so they
stay visible in light/dark tabs.

Use [figma-cli](FIGMA.md) as the primary tool for inspecting and editing Figma,
exporting assets, and checking design changes. [DESIGN.md](DESIGN.md) records the
approved source screens and branding.

The theme link requests the current path with `?theme=light` or `?theme=dark`. A normal request gets a
complete HTML document. An `HX-Request: true` request gets just `#portfolio`, which
HTMX replaces without reloading the document or jumping to the top. The theme
is saved in an HTTP-only, SameSite=Lax cookie. Both the content and theme link
work with JavaScript disabled. HTML responses are `no-store` and vary by cookie
and HTMX headers; history restoration always gets a full document.

Editor tabs contain only visited pages, in opening order. The first visit to
`/` opens only `welcome.md`; `/projects` and `/about` open their own tabs when
followed. A session cookie (`workspace_tabs`, HTTP-only and SameSite=Lax) keeps
the open set through navigation, theme changes, and reloads. Opening an existing
tab does not duplicate it. The close button is a separate POST form: closing an
inactive tab preserves the current page, while closing the active one selects
the previous neighbor (or next if there is no previous). Closing the final
content tab returns to welcome; a lone welcome tab has no close button.
These controls work with JavaScript disabled, and close targets are restricted
to known internal pages. The mobile header continues to hide the tab strip.

Page links and the mobile Back link use ordinary server routes. External
project/contact links open a new tab. Webring links remain same-tab navigation,
with the registered `itzephir/prev`, ring index, and `itzephir/next` URLs.
They are visible in the first viewport without JavaScript, including at 320 px.
On mobile the three links have 44 px touch targets; the header has no brand or
page tabs. Theme switching sits beside the greeting or Back link.

The header uses the same dimensions and control sizes on every route and in
both themes. A stable scrollbar gutter prevents horizontal jumps between short
and long pages. Opening or closing editor tabs only changes the tab strip and
page content, not the header layout.
The fixed-width path beside the wordmark shows `/`, `/projects` or `/about`
for the current page.

The [accepted shell contract](architecture/site-shell.md) defines this as an
architectural boundary. `SiteShell.kt` and `site-shell.css` own the shared shell;
pages provide main content. CI checks its approved HTML structure, ownership,
responsive geometry and navigation behavior before allowing deployment.

The `/dev` gallery and `/dev/panel` management UI use the same shell. The public
gallery shows approved registry projects; owners manage only assigned projects,
and administrators can create/review projects, assign owners and add users.
Account settings support password changes and configured GitHub linking. Dev
documents share visited tabs, themes and no-JavaScript navigation with portfolio
pages. Their [Figma UI v1 frames](DESIGN.md#development-workspace-ui-v1) cover both
themes, responsive layouts, forms, errors and one-time service-token output.
Run the PostgreSQL-backed checks from `project-locator/README.md` as well as the
ordinary website checks when changing this integration.

`Webring.kt` refreshes neighbor names from the ring's public `/itzephir/data`
endpoint in the background, at most every five minutes. HTML always receives
the last known names immediately. Failures retain the cached names and retry
after one minute; the upstream request has a three-second timeout. Outgoing
prev/next URLs never depend on neighbor names or URLs from that response.
Member names are never hardcoded. Before the first successful API response,
generic direction labels keep the links usable.

## Build and verify

```bash
./gradlew check :website:installDist
python3 -m unittest discover -s deploy/tests -p '*_test.py'
npm ci --prefix deploy --ignore-scripts
CHROME_EXECUTABLE="/path/to/Chrome" npm test --prefix deploy
```

The application distribution is `website/build/install/website`. Start it with
`bash website/build/install/website/bin/website` and a JDK/JRE 21+ runtime. The
browser check starts this packaged application, verifies both themes on welcome
and projects at 1440/768/720/719/390/320 px, HTMX switching on every page,
reload/back navigation, logo geometry, self-hosted assets, the first-screen
webring, and the no-JavaScript fallback. Screenshots are saved to
`website/build/browser-check`.

Deployment, service setup, health checks, and rollback are documented in
`../deploy/README.md`.
