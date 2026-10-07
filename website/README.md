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

`src/main/kotlin/com/itzephir/website/Portfolio.kt` is the single source of page
content. Static CSS, images, bundled fonts, and HTMX live in
`src/main/resources/static`. Resource URLs contain a digest to invalidate browser
caches after updates; assets are cached for an hour.

The theme link requests `/?theme=light` or `/?theme=dark`. A normal request gets a
complete HTML document. An `HX-Request: true` request gets just `#portfolio`, which
HTMX replaces without reloading the document or jumping to the top. The theme
is saved in an HTTP-only, SameSite=Lax cookie. Both the content and theme link
work with JavaScript disabled. HTML responses are `no-store` and vary by cookie
and HTMX headers; history restoration always gets a full document.

External project/contact links open a new tab. Webring links remain same-tab
navigation, with the existing previous/index/next URLs preserved.

## Build and verify

```bash
./gradlew check :website:installDist
npm ci --prefix deploy --ignore-scripts
CHROME_EXECUTABLE="/path/to/Chrome" npm test --prefix deploy
```

The application distribution is `website/build/install/website`. Start it with
`bash website/build/install/website/bin/website` and a JDK/JRE 21+ runtime. The
browser check starts this packaged application, verifies desktop/tablet/mobile
layouts, HTMX theme switching, reload persistence, self-hosted assets, webring,
and the no-JavaScript fallback. Screenshots are saved to
`website/build/browser-check`.

Deployment, service setup, health checks, and rollback are documented in
`../deploy/README.md`.
