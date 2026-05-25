import {
  blindLevelUpdateFromWs,
  mapTournamentDetailFromApi,
  TournamentDetailApi
} from './tournament-detail.mapper';
import { DEFAULT_BLIND_LEVELS, TURBO_BLIND_LEVELS, Tournament } from './tournament';

describe('tournament-detail.mapper', () => {
  const turboApi: TournamentDetailApi = {
    id: 't1',
    name: 'Turbo SNG',
    status: 'RUNNING',
    registeredPlayers: 4,
    playersRemaining: 4,
    minPlayers: 2,
    maxPlayers: 9,
    currentLevel: 1,
    currentBlinds: { level: 1, smallBlind: 10, bigBlind: 20, ante: 0 },
    nextBlinds: { level: 2, smallBlind: 15, bigBlind: 30, ante: 0 },
    secondsToNextLevel: 4,
    levelEndTimeEpochMillis: 1_700_000_000_000,
    levelDurationSeconds: 5,
    startingChips: 1500,
    averageStack: 1500,
    buyIn: 100,
    prizePool: 400
  };

  it('maps player standings and table seats from API', () => {
    const api: TournamentDetailApi = {
      ...turboApi,
      players: [
        {
          rank: 1,
          playerId: 'p1',
          playerName: 'Alice',
          chips: 2000,
          status: 'PLAYING'
        },
        {
          rank: 2,
          playerId: 'p2',
          playerName: 'Bot_1',
          chips: 1000,
          status: 'PLAYING'
        }
      ],
      tables: [
        {
          id: 'table-1',
          tableNumber: 1,
          playerCount: 2,
          isFinalTable: false,
          currentGameId: null,
          players: [
            { id: 'p1', name: 'Alice', chips: 2000, isBot: false },
            { id: 'p2', name: 'Bot_1', chips: 1000, isBot: true }
          ]
        }
      ]
    };
    const t = mapTournamentDetailFromApi(api);
    expect(t.registeredPlayers).toHaveLength(2);
    expect(t.registeredPlayers[0].name).toBe('Alice');
    expect(t.registeredPlayers[1].isBot).toBe(true);
    expect(t.tables[0].players).toHaveLength(2);
  });

  it('maps API detail to tournament with level end time', () => {
    const t = mapTournamentDetailFromApi(turboApi);
    expect(t.levelEndTime).toBe(1_700_000_000_000);
    expect(t.config.levelDurationSeconds).toBe(5);
    expect(t.config.blindLevels).toEqual(TURBO_BLIND_LEVELS.map(l => ({ ...l, durationMinutes: 1 })));
    expect(t.nextBlinds?.smallBlind).toBe(15);
  });

  it('uses default blind levels for non-turbo structure', () => {
    const api: TournamentDetailApi = {
      ...turboApi,
      currentBlinds: { level: 1, smallBlind: 25, bigBlind: 50, ante: 0 }
    };
    const t = mapTournamentDetailFromApi(api);
    expect(t.config.blindLevels[0].smallBlind).toBe(DEFAULT_BLIND_LEVELS[0].smallBlind);
  });

  it('applies WS blind level patch with next blinds and timer', () => {
    const active: Tournament = mapTournamentDetailFromApi(turboApi);
    const patch = blindLevelUpdateFromWs({
      newLevel: 2,
      smallBlind: 15,
      bigBlind: 30,
      ante: 0,
      nextLevel: 3,
      nextSmallBlind: 25,
      nextBigBlind: 50,
      nextAnte: 0,
      levelEndTimeEpochMillis: 1_700_000_005_000,
      levelDurationSeconds: 5,
      playersRemaining: 3
    }, active);

    expect(patch?.currentLevel).toBe(2);
    expect(patch?.currentBlinds?.smallBlind).toBe(15);
    expect(patch?.nextBlinds?.smallBlind).toBe(25);
    expect(patch?.levelEndTime).toBe(1_700_000_005_000);
    expect(patch?.remainingPlayers).toBe(3);
  });
});
