/**
 * Live API tournament E2E (runs in CI when CYPRESS_LIVE_TOURNAMENT=true).
 * Requires backend on :8080 and frontend proxying /api.
 */
const runLive = Cypress.env('LIVE_TOURNAMENT') === true
  || Cypress.env('LIVE_TOURNAMENT') === 'true';

(runLive ? describe : describe.skip)('Tournament flow (live API)', () => {
  beforeEach(() => {
    cy.clearAllStorage();
  });

  it('TF-01: lobby → table for running Sit & Go', () => {
    cy.seedRunningSitAndGo({ maxPlayers: 2 }).then(({ tournamentId }) => {
      cy.visit(`/tournaments/${tournamentId}`);
      cy.get('[data-cy="tournament-lobby"]', { timeout: 15000 }).should('be.visible');
      cy.get('[data-cy="go-to-table-btn"]', { timeout: 20000 }).should('be.visible').click();
      cy.url().should('include', `/tournaments/${tournamentId}/play`);
      cy.get('[data-cy="tournament-table"]', { timeout: 30000 }).should('be.visible');
      cy.get('[data-cy="blind-timer"]').should('be.visible');
      cy.get('[data-cy="poker-table"]').should('be.visible');
    });
  });

  it('TF-02: register from lobby (mocked detail)', () => {
    // Registration-only path without filling the SNG (avoids second live user timing).
    cy.loginViaApi().then(({ userId }) => {
      const tournamentId = 'e2e-register-tournament';
      cy.intercept('GET', `/api/v1/tournaments/${tournamentId}`, {
        statusCode: 200,
        body: {
          id: tournamentId,
          name: 'E2E Register Test',
          type: 'SIT_AND_GO',
          status: 'REGISTERING',
          registeredPlayers: 1,
          playersRemaining: 1,
          minPlayers: 2,
          maxPlayers: 9,
          currentLevel: 1,
          currentBlinds: { level: 1, smallBlind: 10, bigBlind: 20, ante: 0 },
          nextBlinds: null,
          secondsToNextLevel: 300,
          startingChips: 1500,
          averageStack: 1500,
          buyIn: 100,
          prizePool: 100,
          players: []
        }
      }).as('getTournament');

      cy.intercept('POST', `/api/v1/tournaments/${tournamentId}/register`, {
        statusCode: 200,
        body: {
          id: tournamentId,
          name: 'E2E Register Test',
          status: 'REGISTERING',
          registeredPlayers: 2,
          playersRemaining: 2,
          minPlayers: 2,
          maxPlayers: 9,
          currentLevel: 1,
          currentBlinds: { level: 1, smallBlind: 10, bigBlind: 20, ante: 0 },
          nextBlinds: null,
          secondsToNextLevel: 300,
          startingChips: 1500,
          averageStack: 1500,
          buyIn: 100,
          prizePool: 200,
          players: [
            {
              rank: 1,
              playerId: userId,
              playerName: 'TestPlayer',
              chips: 1500,
              status: 'REGISTERED'
            }
          ]
        }
      }).as('register');

      cy.visit(`/tournaments/${tournamentId}`);
      cy.wait('@getTournament');
      cy.get('[data-cy="register-btn"]').click();
      cy.wait('@register');
      cy.contains('You are registered', { timeout: 10000 }).should('be.visible');
    });
  });
});
