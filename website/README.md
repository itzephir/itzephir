# itzephir.com website

The portfolio is a Compose Multiplatform application targeting Kotlin/Wasm.
The HTML first screen displays immediately while Compose loads in the background.
The interactive interface is rendered by Compose into a canvas. Keep the hero
copy in `index.html` aligned with `App.kt` when editing personal information.

## Run locally

```bash
./gradlew :website:wasmJsBrowserDevelopmentRun
```

Do not open `index.html` directly with a `file://` URL. WebAssembly modules and
their resources must be loaded through the development server.

## Build for production

```bash
./gradlew check :website:wasmJsBrowserDistribution
deploy/prepare-web-release.sh
```

The deployable static files are generated in:

```text
website/build/dist/wasmJs/productionExecutable
```

The preparation step requires Node.js 24+ and adds inline startup styles, early
Wasm loading, a content-hashed entry script, and precompressed Brotli/gzip files.
It is safe to run more than once. Development builds keep their normal external
stylesheet and unversioned entry point.

The production server must:

- serve `.wasm` files as `application/wasm`;
- enable gzip, Brotli, or Zstandard compression for Wasm and JavaScript;
- keep `index.html` on a short cache lifetime;
- use long immutable caching for hashed `.wasm` files;
- redirect HTTP and `www.itzephir.com` to `https://itzephir.com`.

The versioned nginx configuration and CI/CD details live in `../deploy`.
