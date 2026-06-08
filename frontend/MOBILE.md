# Native apps (Android / iOS / desktop) from the Angular web app

The same Angular build is wrapped in native shells — **no second client codebase**. Mobile uses
[Capacitor](https://capacitorjs.com), desktop can use [Tauri](https://tauri.app) or Electron.

## What's already wired (committed)

- **`environment.native.ts`** — absolute `apiUrl` / `wsUrl` (native shells don't share the backend origin, so the
  browser build's relative `/api` and `/ws` don't resolve). **Set `API_BASE`** to your deployed backend (HTTPS).
- **`angular.json` → `native` configuration** + **`npm run build:native`** (outputs `dist/texas-holdem-frontend`).
- **`capacitor.config.ts`** (`appId: com.truholdem.app`, `androidScheme: https`).
- **`@capacitor/core|cli|android|ios`** in `package.json`.
- **Backend CORS + WebSocket allow-list** already include the native origins
  (`http(s)://localhost`, `capacitor://localhost`, `ionic://localhost`, `tauri://localhost`, `https://tauri.localhost`)
  — see `CorsConfiguration` and `WebSocketConfig`.

The `android/`, `ios/`, `src-tauri/` folders are **generated on demand** (they need the Android SDK / Xcode / Rust)
and are git-ignored.

## Android (Capacitor)

Requires the Android SDK (Android Studio).

```bash
cd frontend
# one-time: create the native project
npx cap add android
# build the web app + copy it into the shell
npm run cap:sync
# open Android Studio (build / run / sign an APK or AAB there)
npm run cap:android
```

## iOS (Capacitor)

Requires **macOS + Xcode** (Apple toolchain is macOS-only) and an Apple Developer account.

```bash
cd frontend
npx cap add ios
npm run cap:sync
npm run cap:ios   # opens Xcode
```

## Desktop — Windows / macOS / Linux (Tauri)

The Tauri project is already scaffolded in **`src-tauri/`** (config, Rust entry, default icons, capabilities) and
is committed — only `src-tauri/target` (Rust build output) is git-ignored. It bundles the **`native` Angular build**
into a small native binary using the OS WebView (WebView2 on Windows).

**Build prerequisites** (on the build machine — Tauri can only build for the host OS):
- [Rust toolchain](https://www.rust-lang.org/tools/install) (`cargo`)
- **Windows:** Microsoft C++ Build Tools (MSVC) + WebView2 runtime (preinstalled on Win10/11)
- macOS: Xcode command-line tools · Linux: `webkit2gtk` + build essentials

```bash
cd frontend
npm run tauri:dev      # run the desktop app against the live dev server (http://localhost:4200)
npm run tauri:build    # produce the installer for the host OS
```

On **Windows**, `tauri:build` outputs to `src-tauri/target/release/bundle/`:
- `nsis/TruHoldem_2.0.0_x64-setup.exe` (NSIS installer)
- `msi/TruHoldem_2.0.0_x64_en-US.msi` (WiX installer)
- plus the raw `TruHoldem.exe`.

> `beforeBuildCommand` runs `npm run build:native`, so the web app is rebuilt with absolute API/WS URLs before
> bundling. Set `API_BASE` in `environment.native.ts` (or replace the file in CI) to the deployed backend first.
> For a code-signed installer, configure a signing certificate in `tauri.conf.json` (`bundle.windows.certificateThumbprint`).

## Gotchas

- **HTTPS is mandatory.** Android blocks cleartext by default and iOS ATS requires TLS — the backend must be
  served over `https://` / `wss://`.
- **JWT, not cookies.** Auth is a bearer token, so cross-origin works; but `localStorage` in a WebView is not
  secure — consider moving the token to `@capacitor/preferences` (Keychain / EncryptedSharedPreferences).
- **WebSocket in a WebView** is the most likely source of friction (reconnect on app background, `wss://`,
  mixed content). Test the live table thoroughly.
- **Store policy for real-money gambling.** Apple (stricter) and Google restrict real-money gaming to licensed
  operators with regional gating, and often disallow WebView-only gambling apps. This is a **product/compliance**
  decision, not a technical one — for real money, plan for off-store distribution or a licensed entity + geo-gating.
