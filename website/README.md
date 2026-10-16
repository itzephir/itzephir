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

`Webring.kt` refreshes neighbor names from the ring's public `/itzephir/data`
endpoint in the background, at most every five minutes. HTML always receives
the last known names immediately. Failures retain the cached names and retry
after one minute; the upstream request has a three-second timeout. Outgoing
prev/next URLs never depend on neighbor names or URLs from that response.

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
