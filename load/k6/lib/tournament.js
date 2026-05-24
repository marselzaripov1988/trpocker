import { check, sleep } from 'k6';
import { checkOk, get, parseJson, post, apiBase } from './http.js';

export function createTournament(token, { name, maxPlayers }) {
  const api = apiBase();
  const res = post(`${api}/v1/tournaments`, {
    name,
    type: 'SIT_AND_GO',
    startingChips: 1500,
    minPlayers: 2,
    maxPlayers,
    buyIn: 0,
    blindStructureType: 'TURBO',
  }, token, { name: 'tournament_create' });

  checkOk(res, 'tournament_create', [201]);
  const body = parseJson(res);
  return body?.id;
}

export function registerForTournament(token, tournamentId, playerId, playerName) {
  const api = apiBase();
  const res = post(
    `${api}/v1/tournaments/${tournamentId}/register`,
    { playerId, playerName },
    token,
    { name: 'tournament_register' }
  );
  checkOk(res, 'tournament_register', [200]);
}

export function getTournamentDetail(token, tournamentId) {
  const api = apiBase();
  const res = get(`${api}/v1/tournaments/${tournamentId}`, token, { name: 'tournament_detail' });
  checkOk(res, 'tournament_detail', [200]);
  return parseJson(res);
}

export function waitForRunning(token, tournamentId, timeoutSec = 90) {
  const deadline = Date.now() + timeoutSec * 1000;
  while (Date.now() < deadline) {
    const detail = getTournamentDetail(token, tournamentId);
    if (detail?.status === 'RUNNING' || detail?.status === 'FINAL_TABLE') {
      return detail;
    }
    if (detail?.status === 'STARTING') {
      sleep(0.5);
      continue;
    }
    sleep(0.5);
  }
  check(null, { 'tournament reached RUNNING': () => false });
  return null;
}

export function startTableHand(token, tournamentId, tableId) {
  const api = apiBase();
  const res = post(
    `${api}/v1/tournaments/${tournamentId}/tables/${tableId}/hand`,
    {},
    token,
    { name: 'tournament_hand' }
  );
  checkOk(res, 'tournament_hand', [200]);
  return parseJson(res);
}

export function getGame(token, gameId) {
  const api = apiBase();
  const res = get(`${api}/v1/poker/game/${gameId}`, token, { name: 'game_get' });
  checkOk(res, 'game_get', [200]);
  return parseJson(res);
}

export function playerAction(token, gameId, playerId, action, amount = 0) {
  const api = apiBase();
  const res = post(
    `${api}/v1/poker/game/${gameId}/player/${playerId}/action`,
    { playerId, action, amount },
    token,
    { name: 'game_action' }
  );
  return res;
}

export function pickLegalAction(game, playerId) {
  const player = game.players?.find((p) => p.id === playerId);
  if (!player || player.folded) {
    return null;
  }
  const currentBet = game.currentBet ?? 0;
  const playerBet = player.currentBet ?? 0;
  const toCall = currentBet - playerBet;
  if (toCall <= 0) {
    return 'CHECK';
  }
  if (player.chips > toCall) {
    return 'CALL';
  }
  return 'FOLD';
}

export function playUntilFinishedOrLimit(token, game, userId, maxActions = 40) {
  let current = game;
  let actions = 0;

  while (current && !current.isFinished && actions < maxActions) {
    const idx = current.currentPlayerIndex ?? 0;
    const actor = current.players?.[idx];
    if (!actor) {
      break;
    }

    if (actor.id === userId) {
      const action = pickLegalAction(current, userId);
      if (!action) {
        break;
      }
      const res = playerAction(token, current.id, userId, action, 0);
      check(res, {
        'game_action ok': (r) => r.status === 200,
      });
      current = parseJson(res);
      actions += 1;
      sleep(0.05);
      continue;
    }

    // Bots: trigger bot endpoint if it's a bot's turn
    if (actor.isBot) {
      const api = apiBase();
      const botRes = post(
        `${api}/v1/poker/game/${current.id}/bot/${actor.id}/action`,
        {},
        token,
        { name: 'game_bot_action' }
      );
      if (botRes.status === 200) {
        current = parseJson(botRes);
      } else {
        current = getGame(token, current.id);
      }
      actions += 1;
      sleep(0.05);
      continue;
    }

    sleep(0.1);
    current = getGame(token, current.id);
  }

  return current;
}
