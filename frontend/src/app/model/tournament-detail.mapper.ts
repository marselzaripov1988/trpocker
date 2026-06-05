import {
  BlindLevel,
  DEFAULT_BLIND_LEVELS,
  DEFAULT_PRIZE_STRUCTURE,
  TURBO_BLIND_LEVELS,
  Tournament,
  TournamentConfig,
  TournamentPlayer,
  TournamentStatus,
  TournamentTable
} from './tournament';

export interface BlindLevelInfoApi {
  level: number;
  smallBlind: number;
  bigBlind: number;
  ante: number;
}

export interface TournamentPlayerEntryApi {
  rank: number;
  playerId: string;
  playerName: string;
  chips: number;
  status: string;
  finishPosition?: number | null;
  prizeWon?: number | null;
  rebuysUsed?: number;
  addOnsUsed?: number;
  bountiesCollected?: number;
}

export interface TournamentTablePlayerApi {
  id: string;
  name: string;
  chips: number;
  isBot: boolean;
}

export interface TournamentDetailApi {
  id: string;
  name: string;
  type?: string;
  status: TournamentStatus;
  /** Registered player count. */
  registeredPlayers: number;
  playersRemaining: number;
  minPlayers: number;
  maxPlayers: number;
  currentLevel: number;
  currentBlinds: BlindLevelInfoApi;
  nextBlinds: BlindLevelInfoApi | null;
  secondsToNextLevel: number;
  levelEndTimeEpochMillis?: number | null;
  levelDurationSeconds?: number;
  startingChips: number;
  averageStack: number;
  chipLeaderStack?: number;
  chipLeaderName?: string | null;
  buyIn: number;
  prizePool: number;
  payoutStructure?: number[];
  tableCount?: number;
  tables?: TournamentTableSummaryApi[];
  /** Standings (Phase 5a); empty when tournament is very large. */
  players?: TournamentPlayerEntryApi[];
  createdAt?: string;
  startTime?: string | null;
  /** Scheduled auto-start time (null = manual). */
  scheduledStart?: string | null;
  /** Start only at the slot if the table is full (else postpone a day). */
  requireFullToStart?: boolean;
  /** When true, this PYRAMID lets players buy guaranteed higher-level seats before start ("tickets"). */
  pyramidBuyUpEnabled?: boolean;
}

export interface TournamentTableSummaryApi {
  id: string;
  tableNumber: number;
  playerCount: number;
  isFinalTable: boolean;
  currentGameId?: string | null;
  players?: TournamentTablePlayerApi[];
}

function isBotName(name: string): boolean {
  return name?.length >= 3 && name.substring(0, 3).toLowerCase() === 'bot';
}

function isEliminatedStatus(status: string): boolean {
  return status === 'ELIMINATED' || status === 'FINISHED' || status === 'WITHDRAWN';
}

function baseTournamentPlayer(
  partial: Partial<TournamentPlayer> & Pick<TournamentPlayer, 'id' | 'name' | 'chips'>
): TournamentPlayer {
  return {
    hand: [],
    betAmount: 0,
    totalBetInRound: 0,
    folded: false,
    isAllIn: false,
    hasActed: false,
    seatPosition: 0,
    tablesPlayed: 0,
    handsWon: 0,
    biggestPot: 0,
    knockouts: 0,
    isEliminated: false,
    isBot: false,
    canAct() {
      return !this.folded && !this.isAllIn && this.chips > 0;
    },
    getDisplayName() {
      return this.name || 'Anonymous';
    },
    isHuman() {
      return !this.isBot;
    },
    getStatusText() {
      return '';
    },
    ...partial
  };
}

function mapPlayerEntry(entry: TournamentPlayerEntryApi): TournamentPlayer {
  return baseTournamentPlayer({
    id: entry.playerId,
    name: entry.playerName,
    chips: entry.chips,
    isBot: isBotName(entry.playerName),
    rank: entry.rank,
    finishPosition: entry.finishPosition ?? undefined,
    prizeMoney: entry.prizeWon ?? undefined,
    isEliminated: isEliminatedStatus(entry.status),
    knockouts: entry.bountiesCollected ?? 0
  });
}

function mapTablePlayer(p: TournamentTablePlayerApi): TournamentPlayer {
  return baseTournamentPlayer({
    id: p.id,
    name: p.name,
    chips: p.chips,
    isBot: p.isBot
  });
}

function blindFromInfo(info: BlindLevelInfoApi, durationMinutes: number): BlindLevel {
  return {
    level: info.level,
    smallBlind: info.smallBlind,
    bigBlind: info.bigBlind,
    ante: info.ante ?? 0,
    durationMinutes
  };
}

function resolveBlindLevels(api: TournamentDetailApi, durationMinutes: number): BlindLevel[] {
  const turboFirst = TURBO_BLIND_LEVELS[0];
  if (
    api.currentBlinds.smallBlind === turboFirst.smallBlind &&
    api.currentBlinds.bigBlind === turboFirst.bigBlind
  ) {
    return TURBO_BLIND_LEVELS.map(l => ({ ...l, durationMinutes }));
  }
  return DEFAULT_BLIND_LEVELS.map(l => ({ ...l, durationMinutes }));
}

export function mapTournamentDetailFromApi(api: TournamentDetailApi): Tournament {
  const levelDurationSeconds = api.levelDurationSeconds
    ?? Math.max(60, api.secondsToNextLevel || 300);
  const durationMinutes = Math.max(1, Math.round(levelDurationSeconds / 60));
  const levelEndTime = api.levelEndTimeEpochMillis
    ?? Date.now() + api.secondsToNextLevel * 1000;
  const levelStartTime = levelEndTime - levelDurationSeconds * 1000;

  const blindLevels = resolveBlindLevels(api, durationMinutes);
  const currentBlinds = blindFromInfo(api.currentBlinds, durationMinutes);
  const nextBlinds = api.nextBlinds
    ? blindFromInfo(api.nextBlinds, durationMinutes)
    : null;

  const config: TournamentConfig = {
    name: api.name,
    buyIn: api.buyIn,
    startingChips: api.startingChips,
    maxPlayers: api.maxPlayers,
    minPlayers: api.minPlayers,
    blindLevels,
    levelDurationMinutes: durationMinutes,
    levelDurationSeconds,
    breakAfterLevels: 0,
    breakDurationMinutes: 0,
    prizeStructure: DEFAULT_PRIZE_STRUCTURE,
    lateRegistrationLevels: 0,
    rebuyAllowed: false,
    rebuyLevels: 0,
    addOnAllowed: false
  };

  const registeredPlayers: TournamentPlayer[] = (api.players ?? []).map(mapPlayerEntry);

  const tables: TournamentTable[] = (api.tables ?? []).map(t => ({
    id: t.id,
    tableNumber: t.tableNumber,
    players: (t.players ?? []).map(mapTablePlayer),
    currentHandNumber: 0,
    dealerPosition: 0,
    isActive: true,
    currentGameId: t.currentGameId ?? null
  }));

  const chipStacks = registeredPlayers.filter(p => !p.isEliminated).map(p => p.chips ?? 0);
  const smallestStack = chipStacks.length > 0 ? Math.min(...chipStacks) : 0;

  const now = Date.now();

  return {
    id: api.id,
    name: api.name,
    status: api.status,
    type: api.type,
    pyramidBuyUpEnabled: api.pyramidBuyUpEnabled ?? false,
    config,
    currentLevel: api.currentLevel,
    currentBlinds,
    nextBlinds,
    levelStartTime,
    levelEndTime,
    nextBreakAtLevel: 0,
    registeredPlayers,
    remainingPlayers: api.playersRemaining,
    totalPlayers: api.registeredPlayers,
    tables,
    averageStack: api.averageStack,
    largestStack: api.chipLeaderStack ?? api.averageStack,
    smallestStack,
    totalChipsInPlay: api.averageStack * api.playersRemaining,
    handsPlayed: 0,
    prizePool: api.prizePool,
    startTime: api.startTime ? Date.parse(api.startTime) : undefined,
    createdAt: api.createdAt ? Date.parse(api.createdAt) : now,
    updatedAt: now
  };
}

/** Maps WS blind-level payload fields onto partial tournament state. */
export function blindLevelUpdateFromWs(
  data: Record<string, unknown>,
  active: Tournament
): Partial<Tournament> | null {
  const newLevel = Number(data['newLevel']);
  if (!Number.isFinite(newLevel)) {
    return null;
  }

  const smallBlind = Number(data['smallBlind']);
  const bigBlind = Number(data['bigBlind']);
  const ante = Number(data['ante'] ?? 0);
  const durationMinutes = active.config.levelDurationMinutes;

  const currentBlinds: BlindLevel = Number.isFinite(smallBlind) && Number.isFinite(bigBlind)
    ? {
        level: newLevel,
        smallBlind,
        bigBlind,
        ante: Number.isFinite(ante) ? ante : 0,
        durationMinutes
      }
    : (active.config.blindLevels.find(b => b.level === newLevel) ?? active.currentBlinds);

  let nextBlinds: BlindLevel | null = active.nextBlinds ?? null;
  const nextSb = Number(data['nextSmallBlind']);
  const nextBb = Number(data['nextBigBlind']);
  if (Number.isFinite(nextSb) && Number.isFinite(nextBb)) {
    nextBlinds = {
      level: Number(data['nextLevel'] ?? newLevel + 1),
      smallBlind: nextSb,
      bigBlind: nextBb,
      ante: Number(data['nextAnte'] ?? 0),
      durationMinutes
    };
  } else {
    const fromConfig = active.config.blindLevels.find(b => b.level === newLevel + 1);
    nextBlinds = fromConfig ?? null;
  }

  const levelDurationSeconds = Number(data['levelDurationSeconds']);
  const levelEndRaw = Number(data['levelEndTimeEpochMillis']);
  const levelEndTime = Number.isFinite(levelEndRaw)
    ? levelEndRaw
    : (Number.isFinite(levelDurationSeconds)
        ? Date.now() + levelDurationSeconds * 1000
        : active.levelEndTime);

  const patch: Partial<Tournament> = {
    currentLevel: newLevel,
    currentBlinds,
    nextBlinds,
    levelEndTime,
    levelStartTime: levelEndTime - (active.config.levelDurationSeconds
      ?? active.config.levelDurationMinutes * 60) * 1000
  };

  if (Number.isFinite(levelDurationSeconds) && levelDurationSeconds > 0) {
    patch.config = {
      ...active.config,
      levelDurationSeconds,
      levelDurationMinutes: Math.max(1, Math.round(levelDurationSeconds / 60))
    };
  }

  const remaining = Number(data['playersRemaining']);
  if (Number.isFinite(remaining)) {
    patch.remainingPlayers = remaining;
  }

  return patch;
}
