# itzephir.com website

The portfolio is a Compose Multiplatform application targeting Kotlin/Wasm.
The HTML file is only the browser shell; the visible interface is rendered by
Compose into a canvas.

## Run locally

```bash
./gradlew :website:wasmJsBrowserDevelopmentRun
```

Do not open `index.html` directly with a `file://` URL. WebAssembly modules and
their resources must be loaded through the development server.

## Build for production

```bash
./gradlew check :website:wasmJsBrowserDistribution
```

The deployable static files are generated in:

```text
website/build/dist/wasmJs/productionExecutable
```

The production server must:

- serve `.wasm` files as `application/wasm`;
- enable gzip, Brotli, or Zstandard compression for Wasm and JavaScript;
- keep `index.html` on a short cache lifetime;
- use long immutable caching for hashed `.wasm` files;
- redirect HTTP and `www.itzephir.com` to `https://itzephir.com`.

The versioned nginx configuration and CI/CD details live in `../deploy`.
