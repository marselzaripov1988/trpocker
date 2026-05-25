import { mapTournamentDetailFromApi, TournamentDetailApi } from './tournament-detail.mapper';
import {
  finalTableReachedFromWs,
  playerEliminatedFromWs,
  tableRebalancedFromWs
} from './tournament-ws.mapper';

describe('tournament-ws.mapper', () => {
  const baseApi: TournamentDetailApi = {
    id: 't1',
    name: 'Test',
    status: 'RUNNING',
    registeredPlayers: 4,
    playersRemaining: 4,
    minPlayers: 2,
    maxPlayers: 9,
    currentLevel: 1,
    currentBlinds: { level: 1, smallBlind: 10, bigBlind: 20, ante: 0 },
    nextBlinds: null,
    secondsToNextLevel: 300,
    startingChips: 1500,
    averageStack: 1500,
    buyIn: 100,
    prizePool: 400,
    players: [
      { rank: 1, playerId: 'p1', playerName: 'Alice', chips: 2000, status: 'PLAYING' },
      { rank: 2, playerId: 'p2', playerName: 'Bob', chips: 1500, status: 'PLAYING' },
      { rank: 3, playerId: 'p3', playerName: 'Bot_1', chips: 1000, status: 'PLAYING' },
      { rank: 4, playerId: 'p4', playerName: 'Bot_2', chips: 500, status: 'PLAYING' }
    ],
    tables: [
      {
        id: 'table-1',
        tableNumber: 1,
        playerCount: 2,
        isFinalTable: false,
        players: [
          { id: 'p1', name: 'Alice', chips: 2000, isBot: false },
          { id: 'p2', name: 'Bob', chips: 1500, isBot: false }
        ]
      },
      {
        id: 'table-2',
        tableNumber: 2,
        playerCount: 2,
        isFinalTable: false,
        players: [
          { id: 'p3', name: 'Bot_1', chips: 1000, isBot: true },
          { id: 'p4', name: 'Bot_2', chips: 500, isBot: true }
        ]
      }
    ]
  };

  it('marks eliminated player and removes from tables', () => {
    const active = mapTournamentDetailFromApi(baseApi);
    const patch = playerEliminatedFromWs({
      playerId: 'p4',
      finishPosition: 4,
      prize: 0,
      playersRemaining: 3
    }, active);

    expect(patch?.remainingPlayers).toBe(3);
    const eliminated = patch?.registeredPlayers?.find(p => p.id === 'p4');
    expect(eliminated?.isEliminated).toBe(true);
    expect(eliminated?.finishPosition).toBe(4);
    expect(patch?.tables?.every(t => !t.players.some(p => p.id === 'p4'))).toBe(true);
  });

  it('moves players between known tables and closes tables', () => {
    const active = mapTournamentDetailFromApi(baseApi);
    const result = tableRebalancedFromWs({
      playerMoves: [{ playerId: 'p2', fromTableId: 'table-1', toTableId: 'table-2' }],
      closedTableIds: [],
      activeTableCount: 2,
      finalTableFormed: false
    }, active);

    expect(result.patch).not.toBeNull();
    const t1 = result.patch!.tables!.find(t => t.id === 'table-1');
    const t2 = result.patch!.tables!.find(t => t.id === 'table-2');
    expect(t1?.players.map(p => p.id)).toEqual(['p1']);
    expect(t2?.players.map(p => p.id).sort()).toEqual(['p2', 'p3', 'p4'].sort());
  });

  it('returns null when target table is unknown', () => {
    const active = mapTournamentDetailFromApi(baseApi);
    const result = tableRebalancedFromWs({
      playerMoves: [{ playerId: 'p2', fromTableId: 'table-1', toTableId: 'table-new' }],
      closedTableIds: [],
      activeTableCount: 2
    }, active);
    expect(result.patch).toBeNull();
  });

  it('sets FINAL_TABLE status and myNewTableNumber for self move', () => {
    const active = mapTournamentDetailFromApi(baseApi);
    const result = tableRebalancedFromWs({
      playerMoves: [{ playerId: 'p1', fromTableId: 'table-1', toTableId: 'table-2' }],
      closedTableIds: ['table-1'],
      activeTableCount: 1,
      finalTableFormed: true,
      playersRemaining: 4
    }, active, 'p1');

    expect(result.patch?.status).toBe('FINAL_TABLE');
    expect(result.patch?.tables).toHaveLength(1);
    expect(result.myNewTableNumber).toBe(2);
  });

  it('patches final table status from FINAL_TABLE_REACHED', () => {
    const active = mapTournamentDetailFromApi(baseApi);
    const patch = finalTableReachedFromWs({ playersRemaining: 9 }, active);
    expect(patch.status).toBe('FINAL_TABLE');
    expect(patch.remainingPlayers).toBe(9);
  });
});
