/**
 * Environment for native (Capacitor / Tauri) builds.
 *
 * Native apps are NOT served from the backend's origin (they load from capacitor://localhost / file://), so the
 * relative '/api' and '/ws' used by the browser build do not resolve. They must be ABSOLUTE URLs pointing at the
 * deployed backend, which must (a) be served over HTTPS/WSS and (b) allow the native origins via CORS
 * (capacitor://localhost, http(s)://localhost, ionic://localhost — see CorsConfiguration on the backend).
 *
 * Set API_BASE to the real backend before building, or override it in CI by replacing this file.
 */
const API_BASE = 'https://api.truholdem.example';

export const environment = {
  production: true,
  apiUrl: `${API_BASE}/api`,
  wsUrl: `${API_BASE}/ws`,

  // OAuth2 URLs (absolute for the native WebView)
  googleOAuthUrl: `${API_BASE}/api/auth/oauth2/authorize/google`,
  githubOAuthUrl: `${API_BASE}/api/auth/oauth2/authorize/github`,

  defaultStartingChips: 1000,
  defaultSmallBlind: 10,
  defaultBigBlind: 20,

  cardAnimationDuration: 500,
  actionTimeout: 30000,
  botActionDelay: 800,

  enableWebSocket: true,
  tournamentShardCount: 16,
  enableSoundEffects: true,
  enableAnimations: true,
  enableHandHistory: true,
  enableStatistics: true,

  showDebugInfo: false,
  logApiCalls: false
};
