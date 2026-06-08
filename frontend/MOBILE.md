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

## Desktop (Tauri)

Requires the [Rust toolchain](https://www.rust-lang.org/tools/install).

```bash
cd frontend
npm i -D @tauri-apps/cli
npx tauri init   # frontendDist = dist/texas-holdem-frontend, devUrl = http://localhost:4200
npm run build:native && npx tauri build   # Win/macOS/Linux bundles
```

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
