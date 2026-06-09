/** Minimal API shapes for Cypress intercepts (matches backend v1). */

// `**`-prefixed glob so cy.intercept matches the request's FULL URL (http://host/api/...) regardless of
// origin/port. A bare '/api/...' path does NOT match the full URL in Cypress, so the request leaked to the
// real backend → 401 → logout → redirect to /auth/login, and cy.wait('@get…') saw "no request ever occurred".
export const API_TOURNAMENTS = '**/api/v1/tournaments';

export function listSummaryItem(overrides: Record<string, unknown> = {}) {
  return {
    id: overrides['id'] ?? 'test-tournament-123',
    name: overrides['name'] ?? 'Sunday Million Test',
    type: 'SIT_AND_GO',
    status: overrides['status'] ?? 'REGISTERING',
    registeredPlayers: overrides['registeredPlayers'] ?? 2,
    maxPlayers: overrides['maxPlayers'] ?? 9,
    buyIn: overrides['buyIn'] ?? 100,
    prizePool: overrides['prizePool'] ?? 200,
    currentLevel: overrides['currentLevel'] ?? 1,
    ...overrides
  };
}

export function detailResponse(overrides: Record<string, unknown> = {}) {
  const status = (overrides['status'] as string) ?? 'REGISTERING';
  return {
    id: overrides['id'] ?? 'test-tournament-123',
    name: overrides['name'] ?? 'Sunday Million Test',
    type: 'SIT_AND_GO',
    status,
    registeredPlayers: overrides['registeredPlayers'] ?? 2,
    playersRemaining: overrides['playersRemaining'] ?? 2,
    minPlayers: overrides['minPlayers'] ?? 2,
    maxPlayers: overrides['maxPlayers'] ?? 9,
    currentLevel: overrides['currentLevel'] ?? 1,
    currentBlinds: overrides['currentBlinds'] ?? {
      level: 1,
      smallBlind: 10,
      bigBlind: 20,
      ante: 0
    },
    nextBlinds: overrides['nextBlinds'] ?? { level: 2, smallBlind: 15, bigBlind: 30, ante: 0 },
    secondsToNextLevel: overrides['secondsToNextLevel'] ?? 300,
    levelEndTimeEpochMillis: overrides['levelEndTimeEpochMillis'] ?? Date.now() + 300_000,
    levelDurationSeconds: overrides['levelDurationSeconds'] ?? 300,
    startingChips: overrides['startingChips'] ?? 1500,
    averageStack: overrides['averageStack'] ?? 1500,
    chipLeaderStack: overrides['chipLeaderStack'] ?? 1500,
    chipLeaderName: overrides['chipLeaderName'] ?? 'Alice',
    buyIn: overrides['buyIn'] ?? 100,
    prizePool: overrides['prizePool'] ?? 200,
    tableCount: overrides['tableCount'] ?? (status === 'RUNNING' ? 1 : 0),
    tables: overrides['tables'] ?? (status === 'RUNNING'
      ? [{
          id: 'table-1',
          tableNumber: 1,
          playerCount: 2,
          isFinalTable: false,
          currentGameId: null,
          players: [
            { id: 'current-user-id', name: 'TestPlayer', chips: 1500, isBot: false },
            { id: 'bot-1', name: 'Bot_1', chips: 1500, isBot: true }
          ]
        }]
      : []),
    players: overrides['players'] ?? [
      {
        rank: 1,
        playerId: 'current-user-id',
        playerName: 'TestPlayer',
        chips: 1500,
        status: 'PLAYING'
      },
      {
        rank: 2,
        playerId: 'bot-1',
        playerName: 'Bot_1',
        chips: 1500,
        status: 'PLAYING'
      }
    ],
    ...overrides
  };
}
