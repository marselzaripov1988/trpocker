/**
 * k6 load test: tournament table REST flow (register → hand → player actions).
 *
 * Env:
 *   BASE_URL          default http://localhost:8080
 *   SCENARIO          smoke | load (default smoke)
 *   VUS               players per tournament (default 4)
 *   K6_PASSWORD       default LoadTest123!
 *   ACTION_ROUNDS     max actions per VU (default 30)
 *   SETUP_STAGGER_SECONDS  delay between setup user registrations (default 13; use 0 in CI)
 */
import { sleep } from 'k6';
import { registerAndLogin, fetchProfile, login } from './lib/auth.js';
import {
  createTournament,
  registerForTournament,
  tryStartTournament,
  waitForRunning,
  waitForTables,
  startTableHand,
  playUntilFinishedOrLimit,
} from './lib/tournament.js';

const VUS = Number(__ENV.VUS || 4);
const SCENARIO = (__ENV.SCENARIO || 'smoke').toLowerCase();
const ACTION_ROUNDS = Number(__ENV.ACTION_ROUNDS || 30);
const SETUP_STAGGER_SECONDS = Number(__ENV.SETUP_STAGGER_SECONDS ?? 13);
// Creating + starting a tournament is ADMIN-gated; the smoke logs in as a seeded admin for those two calls
// (players still self-register + play as normal users). The CI/k6 stack seeds this admin (docker-compose.k6.yml).
const ADMIN_USER = __ENV.K6_ADMIN_USER || 'k6admin';
const ADMIN_PASSWORD = __ENV.K6_ADMIN_PASSWORD || 'K6AdminPass123!';

export const options = (() => {
  if (SCENARIO === 'load') {
    return {
      scenarios: {
        tournament_load: {
          executor: 'ramping-vus',
          startVUs: 0,
          stages: [
            { duration: '30s', target: VUS },
            { duration: __ENV.DURATION || '60s', target: VUS },
            { duration: '15s', target: 0 },
          ],
          gracefulRampDown: '10s',
        },
      },
      thresholds: {
        http_req_failed: ['rate<0.1'],
        http_req_duration: ['p(95)<3000'],
        'http_req_duration{name:game_action}': ['p(95)<2000'],
      },
    };
  }

  return {
    scenarios: {
      tournament_smoke: {
        executor: 'per-vu-iterations',
        vus: VUS,
        iterations: 1,
        maxDuration: '5m',
      },
    },
    thresholds: {
      http_req_failed: ['rate<0.05'],
      http_req_duration: ['p(95)<5000'],
    },
  };
})();

export function setup() {
  const players = [];
  const runId = Date.now();

  for (let i = 0; i < VUS; i++) {
    if (i > 0 && SETUP_STAGGER_SECONDS > 0) {
      // Auth bucket allows ~5 requests/min per IP when rate limiting is on.
      sleep(SETUP_STAGGER_SECONDS);
    }
    const username = `k6_${runId}_${i}`;
    const session = registerAndLogin(username);
    players.push({ username, token: session.token });
  }

  const admin = login(ADMIN_USER, ADMIN_PASSWORD);
  const tournamentId = createTournament(admin.token, {
    name: `k6-${SCENARIO}-${runId}`,
    maxPlayers: VUS,
  });
  return { tournamentId, players, adminToken: admin.token };
}

export default function (data) {
  const tournamentId = data.tournamentId;
  const player = data.players[__VU - 1];
  const token = player?.token;
  if (!token) {
    return;
  }

  const profile = fetchProfile(token);
  const userId = profile?.id;
  if (!userId) {
    return;
  }

  registerForTournament(token, tournamentId, userId, player.username);

  // Last VU ensures start after all parallel registrations land. Start is admin-gated → use the admin token.
  if (__VU === VUS) {
    sleep(0.5);
    tryStartTournament(data.adminToken, tournamentId);
  }

  const running = waitForRunning(token, tournamentId);
  if (!running) {
    return;
  }

  const detail = waitForTables(token, tournamentId);
  if (!detail?.tables?.length) {
    return;
  }

  const tableId = detail.tables[0].id;
  sleep(0.3);

  const game = startTableHand(token, tournamentId, tableId);
  if (!game?.id) {
    return;
  }

  playUntilFinishedOrLimit(token, game, userId, ACTION_ROUNDS);
  sleep(0.3);
}
