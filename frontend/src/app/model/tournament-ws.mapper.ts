import { Tournament, TournamentPlayer, TournamentStatus } from './tournament';

export interface PlayerMoveWs {
  playerId: string;
  fromTableId: string;
  toTableId: string;
}

export interface TableRebalanceWsResult {
  patch: Partial<Tournament> | null;
  /** Resubscribe tournament table WS when the current user moved. */
  myNewTableNumber?: number;
}

/** Applies PLAYER_ELIMINATED WS payload onto tournament state. */
export function playerEliminatedFromWs(
  data: Record<string, unknown>,
  active: Tournament
): Partial<Tournament> | null {
  const playerId = String(data['playerId'] ?? '');
  if (!playerId) {
    return null;
  }

  const finishPosition = Number(data['finishPosition']);
  const prize = Number(data['prize'] ?? data['prizeWon'] ?? 0);

  const registeredPlayers = active.registeredPlayers.map(p => {
    if (p.id !== playerId) {
      return p;
    }
    p.isEliminated = true;
    p.chips = 0;
    if (Number.isFinite(finishPosition)) {
      p.finishPosition = finishPosition;
    }
    if (Number.isFinite(prize) && prize >= 0) {
      p.prizeMoney = prize;
    }
    return p;
  });

  const tables = active.tables.map(t => ({
    ...t,
    players: t.players.filter(p => p.id !== playerId)
  }));

  const patch: Partial<Tournament> = { registeredPlayers, tables };
  const remaining = Number(data['playersRemaining']);
  if (Number.isFinite(remaining)) {
    patch.remainingPlayers = remaining;
  }
  return patch;
}

/** Applies TABLE_REBALANCED WS payload; returns null when local tables are incomplete. */
export function tableRebalancedFromWs(
  data: Record<string, unknown>,
  active: Tournament,
  myPlayerId?: string
): TableRebalanceWsResult {
  const playerMoves = (data['playerMoves'] as PlayerMoveWs[] | undefined) ?? [];
  const closedTableIds = new Set((data['closedTableIds'] as string[] | undefined) ?? []);
  const finalTableFormed = Boolean(data['finalTableFormed']);

  if (playerMoves.length > 0) {
    for (const move of playerMoves) {
      if (move.toTableId && !active.tables.some(t => t.id === move.toTableId)) {
        return { patch: null };
      }
    }
  }

  let tables = active.tables.map(t => ({
    ...t,
    players: [...t.players]
  }));

  const findPlayer = (playerId: string): TournamentPlayer | undefined => {
    for (const t of tables) {
      const seated = t.players.find(p => p.id === playerId);
      if (seated) {
        return seated;
      }
    }
    return active.registeredPlayers.find(p => p.id === playerId);
  };

  for (const move of playerMoves) {
    const player = findPlayer(move.playerId);
    if (!player) {
      continue;
    }
    if (move.fromTableId) {
      tables = tables.map(t => {
        if (t.id !== move.fromTableId) {
          return t;
        }
        return { ...t, players: t.players.filter(p => p.id !== move.playerId) };
      });
    }
    if (move.toTableId) {
      tables = tables.map(t => {
        if (t.id !== move.toTableId) {
          return t;
        }
        const already = t.players.some(p => p.id === move.playerId);
        return already
          ? t
          : { ...t, players: [...t.players, player] };
      });
    }
  }

  tables = tables.filter(t => !closedTableIds.has(t.id));

  const activeTableCount = Number(data['activeTableCount']);
  if (Number.isFinite(activeTableCount) && activeTableCount !== tables.length && playerMoves.length === 0) {
    return { patch: null };
  }

  const patch: Partial<Tournament> = { tables };
  const remaining = Number(data['playersRemaining']);
  if (Number.isFinite(remaining)) {
    patch.remainingPlayers = remaining;
  }
  if (finalTableFormed) {
    patch.status = 'FINAL_TABLE' as TournamentStatus;
  }

  let myNewTableNumber: number | undefined;
  if (myPlayerId) {
    const myMove = playerMoves.find(m => m.playerId === myPlayerId);
    if (myMove?.toTableId) {
      myNewTableNumber = tables.find(t => t.id === myMove.toTableId)?.tableNumber;
    } else {
      const seated = tables.find(t => t.players.some(p => p.id === myPlayerId));
      if (seated && closedTableIds.size > 0) {
        myNewTableNumber = seated.tableNumber;
      }
    }
  }

  return { patch, myNewTableNumber };
}

/** Applies FINAL_TABLE_REACHED when sent without a full rebalance payload. */
export function finalTableReachedFromWs(
  data: Record<string, unknown>,
  active: Tournament
): Partial<Tournament> {
  const patch: Partial<Tournament> = { status: 'FINAL_TABLE' };
  const remaining = Number(data['playersRemaining']);
  if (Number.isFinite(remaining)) {
    patch.remainingPlayers = remaining;
  }
  const activeTableCount = Number(data['activeTableCount']);
  if (Number.isFinite(activeTableCount) && activeTableCount === 1 && active.tables.length > 1) {
    return patch;
  }
  return patch;
}
