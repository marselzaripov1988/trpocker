import {
  BlindLevel,
  DEFAULT_BLIND_LEVELS,
  DEFAULT_PRIZE_STRUCTURE,
  TURBO_BLIND_LEVELS,
  Tournament,
  TournamentConfig,
  TournamentStatus,
  TournamentTable
} from './tournament';

export interface BlindLevelInfoApi {
  level: number;
  smallBlind: number;
  bigBlind: number;
  ante: number;
}

export interface TournamentDetailApi {
  id: string;
  name: string;
  type?: string;
  status: TournamentStatus;
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
  createdAt?: string;
  startTime?: string | null;
}

export interface TournamentTableSummaryApi {
  id: string;
  tableNumber: number;
  playerCount: number;
  isFinalTable: boolean;
  currentGameId?: string | null;
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

  const tables: TournamentTable[] = (api.tables ?? []).map(t => ({
    id: t.id,
    tableNumber: t.tableNumber,
    players: [],
    currentHandNumber: 0,
    dealerPosition: 0,
    isActive: true,
    currentGameId: t.currentGameId ?? null
  }));

  const now = Date.now();

  return {
    id: api.id,
    name: api.name,
    status: api.status,
    config,
    currentLevel: api.currentLevel,
    currentBlinds,
    nextBlinds,
    levelStartTime,
    levelEndTime,
    nextBreakAtLevel: 0,
    registeredPlayers: [],
    remainingPlayers: api.playersRemaining,
    totalPlayers: api.registeredPlayers,
    tables,
    averageStack: api.averageStack,
    largestStack: api.chipLeaderStack ?? api.averageStack,
    smallestStack: 0,
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
