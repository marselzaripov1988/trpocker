import { mapTournamentListFromApi } from './tournament-list.mapper';

describe('tournament-list.mapper', () => {
  it('maps registeredPlayers to registeredCount', () => {
    const items = mapTournamentListFromApi([
      {
        id: 't1',
        name: 'SNG',
        status: 'REGISTERING',
        registeredPlayers: 7,
        maxPlayers: 9,
        buyIn: 50,
        prizePool: 350,
        currentLevel: 1
      }
    ]);
    expect(items[0].registeredCount).toBe(7);
    expect(items[0].maxPlayers).toBe(9);
  });
});
