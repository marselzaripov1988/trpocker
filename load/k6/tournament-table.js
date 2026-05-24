/**
 * k6 load test: tournament table REST flow (register → hand → player actions).
 *
 * Env:
 *   BASE_URL          default http://localhost:8080
 *   SCENARIO          smoke | load (default smoke)
 *   VUS               players per tournament (default 4)
 *   K6_PASSWORD       default LoadTest123!
 *   ACTION_ROUNDS     max actions per VU (default 30)
 */
import { sleep } from 'k6';
import { registerAndLogin, fetchProfile } from './lib/auth.js';
import {
  createTournament,
  registerForTournament,
  waitForRunning,
  startTableHand,
  playUntilFinishedOrLimit,
} from './lib/tournament.js';

const VUS = Number(__ENV.VUS || 4);
const SCENARIO = (__ENV.SCENARIO || 'smoke').toLowerCase();
const ACTION_ROUNDS = Number(__ENV.ACTION_ROUNDS || 30);

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
  const bootstrapUser = `k6_bootstrap_${Date.now()}`;
  const { token } = registerAndLogin(bootstrapUser);
  const tournamentId = createTournament(token, {
    name: `k6-${SCENARIO}-${Date.now()}`,
    maxPlayers: VUS,
  });
  return { tournamentId };
}

export default function (data) {
  const tournamentId = data.tournamentId;
  const username = `k6_vu${__VU}_${__ITER}_${Date.now()}`;

  const { token } = registerAndLogin(username);
  const profile = fetchProfile(token);
  const userId = profile?.id;
  if (!userId) {
    return;
  }

  registerForTournament(token, tournamentId, userId, username);

  const detail = waitForRunning(token, tournamentId);
  if (!detail?.tables?.length) {
    return;
  }

  const tableId = detail.tables[0].id;
  sleep(0.2);

  const game = startTableHand(token, tournamentId, tableId);
  if (!game?.id) {
    return;
  }

  playUntilFinishedOrLimit(token, game, userId, ACTION_ROUNDS);
  sleep(0.3);
}
