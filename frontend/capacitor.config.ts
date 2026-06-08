import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor wrapper for the Angular app (native Android / iOS shells around the same web build).
 *
 * The web assets come from the `native` Angular build (absolute API/WS URLs — see environment.native.ts).
 * Build + sync: `npm run build:native` then `npx cap sync`. Open the native IDE: `npx cap open android|ios`.
 *
 * androidScheme=https serves the WebView from https://localhost (matches the CORS / WS allow-list entries on the
 * backend). iOS uses capacitor://localhost.
 */
const config: CapacitorConfig = {
  appId: 'com.truholdem.app',
  appName: 'TruHoldem',
  webDir: 'dist/texas-holdem-frontend',
  server: {
    androidScheme: 'https'
  }
};

export default config;
