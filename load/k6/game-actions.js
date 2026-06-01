/**
 * k6 action-path load test: each iteration creates an independent bot game and plays a hand by driving
 * player + bot actions through the REST API. Because every game is independent, a multi-node cluster
 * spreads the games (and their owners) across nodes, so this measures the horizontal-scaling benefit on
 * the write path (the part single-writer + cross-node routing serialize).
 *
 * Env:
 *   BASE_URL       default http://localhost:8081
 *   VUS            peak concurrent players/games (default 20)
 *   RAMP           ramp-up duration (default 10s)
 *   DURATION       steady-state duration (default 30s)
 *   ACTION_ROUNDS  max actions per game before abandoning (default 60)
 *   K6_PASSWORD    default LoadTest123!
 *
 * Requires the target to run with game authorization + rate limiting disabled (see
 * docker-compose.scale.yml) so one token can drive many bot games.
 */
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { apiBase, post, parseJson } from './lib/http.js';
import { registerAndLogin } from './lib/auth.js';

const VUS = Number(__ENV.VUS || 20);
const RAMP = __ENV.RAMP || '10s';
const DURATION = __ENV.DURATION || '30s';
const ACTION_ROUNDS = Number(__ENV.ACTION_ROUNDS || 60);

const actionLatency = new Trend('game_action_latency', true);
const handsStarted = new Counter('hands_started');
const handsFinished = new Counter('hands_finished');

export const options = {
  scenarios: {
    actions: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'http_req_duration{name:game_action}': ['p(95)<2000'],
  },
};

export function setup() {
  // Authorization is disabled on the target, so one token can act on any bot game.
  const session = registerAndLogin(`k6load_${Date.now()}`);
  return { token: session.token };
}

function startGame(token) {
  const players = [
    { name: 'Hero', startingChips: 1000, isBot: false, playerId: null },
    { name: 'Bot1', startingChips: 1000, isBot: true, playerId: null },
    { name: 'Bot2', startingChips: 1000, isBot: true, playerId: null },
    { name: 'Bot3', startingChips: 1000, isBot: true, playerId: null },
  ];
  const res = post(`${apiBase()}/v1/poker/game/start`, players, token, { name: 'start_game' });
  check(res, { 'start_game 2xx': (r) => r.status === 200 || r.status === 201 });
  return parseJson(res);
}

function actor(game) {
  if (!game || !game.players || game.currentPlayerIndex == null) return null;
  return game.players[game.currentPlayerIndex];
}

export default function (data) {
  const token = data.token;
  let game = startGame(token);
  const gameId = game && game.id;
  if (!gameId) {
    sleep(0.5);
    return;
  }
  handsStarted.add(1);

  for (let round = 0; round < ACTION_ROUNDS; round++) {
    if (!game || game.isFinished) break;
    const cp = actor(game);
    if (!cp || !cp.id) break;

    let res;
    if (cp.isBot) {
      res = post(`${apiBase()}/v1/poker/game/${gameId}/bot/${cp.id}/action`, null, token, { name: 'bot_action' });
    } else {
      const toCall = (game.currentBet || 0) - (cp.currentBet || 0);
      const action = toCall > 0 ? 'CALL' : 'CHECK';
      const t0 = Date.now();
      res = post(`${apiBase()}/v1/poker/game/${gameId}/player/${cp.id}/action`,
        { playerId: cp.id, action, amount: 0 }, token, { name: 'game_action' });
      actionLatency.add(Date.now() - t0);
    }

    if (!res || res.status >= 400) break;
    game = parseJson(res);
  }

  if (game && game.isFinished) {
    handsFinished.add(1);
  }
}
