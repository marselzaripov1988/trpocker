import { API_TOURNAMENTS, detailResponse, listSummaryItem } from '../fixtures/tournament-api';

describe('Tournament Mode (mocked API)', () => {
  const mockUser = {
    id: 'current-user-id',
    username: 'TestPlayer',
    email: 'test@example.com',
    firstName: 'Test',
    lastName: 'Player',
    roles: ['USER'],
    totalGamesPlayed: 0,
    totalWinnings: 0
  };

  const openTournament = listSummaryItem({
    id: 'test-tournament-123',
    name: 'Sunday Million Test',
    status: 'REGISTERING',
    registeredPlayers: 2
  });

  const runningTournament = listSummaryItem({
    id: 'running-tournament-456',
    name: 'Fast & Furious',
    status: 'RUNNING',
    registeredPlayers: 4
  });

  beforeEach(() => {
    cy.window().then(win => {
      win.localStorage.setItem('access_token', 'mock-jwt-token');
      win.localStorage.setItem('user', JSON.stringify(mockUser));
    });

    cy.intercept('GET', API_TOURNAMENTS, {
      body: [openTournament, runningTournament]
    }).as('getTournaments');

    cy.intercept('GET', `${API_TOURNAMENTS}/${openTournament.id}`, {
      body: detailResponse({ id: openTournament.id, status: 'REGISTERING', registeredPlayers: 2 })
    }).as('getTournament');

    cy.intercept('GET', `${API_TOURNAMENTS}/${runningTournament.id}`, {
      body: detailResponse({
        id: runningTournament.id,
        name: runningTournament.name,
        status: 'RUNNING',
        registeredPlayers: 4,
        playersRemaining: 4
      })
    }).as('getRunningTournament');
  });

  afterEach(() => {
    cy.clearAllStorage();
  });

  describe('Lobby', () => {
    beforeEach(() => {
      cy.visit('/tournaments');
      cy.wait('@getTournaments');
    });

    it('should display tournaments list', () => {
      cy.get('[data-cy="tournament-list"]').should('be.visible');
      cy.get('[data-cy="list-title"]').should('contain.text', 'Tournament');
      cy.get('[data-cy="tournament-card"]').should('have.length.at.least', 1);
    });

    it('should display open and running sections', () => {
      cy.get('[data-cy="open-tournaments-section"]').should('be.visible');
      cy.get('[data-cy="open-tournaments-section"]')
        .find('[data-cy="tournament-card"]')
        .should('have.length.at.least', 1);
      cy.get('[data-cy="running-tournaments-section"]').should('be.visible');
      cy.get('[data-cy="running-tournaments-section"]')
        .find('[data-cy="tournament-card-running"]')
        .should('have.length.at.least', 1);
    });

    it('should refresh tournaments on button click', () => {
      cy.intercept('GET', API_TOURNAMENTS, {
        body: [openTournament, runningTournament]
      }).as('refreshTournaments');

      cy.get('[data-cy="refresh-btn"]').click();
      cy.wait('@refreshTournaments');
      cy.get('[data-cy="refresh-btn"]').should('not.be.disabled');
    });

    it('should navigate to tournament lobby from card', () => {
      cy.get('[data-cy="tournament-card"]').first().within(() => {
        cy.get('[data-cy="details-btn"]').click();
      });
      cy.url().should('include', `/tournaments/${openTournament.id}`);
      cy.get('[data-cy="tournament-lobby"]').should('be.visible');
    });

    it('should show empty state when no tournaments', () => {
      cy.intercept('GET', API_TOURNAMENTS, { body: [] }).as('emptyTournaments');
      cy.visit('/tournaments');
      cy.wait('@emptyTournaments');
      cy.get('[data-cy="tournament-list"]').should('be.visible');
    });

    it('should show error state on API failure', () => {
      cy.intercept('GET', API_TOURNAMENTS, { statusCode: 500 }).as('failedRequest');
      cy.visit('/tournaments');
      cy.wait('@failedRequest');
      cy.get('[data-cy="error-message"]').should('be.visible');
      cy.get('[data-cy="retry-btn"]').should('be.visible');
    });
  });

  describe('Registration lobby', () => {
    it('should show register button and lobby info', () => {
      cy.visit(`/tournaments/${openTournament.id}`);
      cy.wait('@getTournament');
      cy.get('[data-cy="tournament-lobby"]').should('be.visible');
      cy.get('[data-cy="tournament-name"]').should('contain.text', 'Sunday Million');
      cy.get('[data-cy="register-btn"]').should('be.visible');
    });
  });

  describe('Running table', () => {
    it('should load tournament table from lobby', () => {
      cy.intercept('GET', `${API_TOURNAMENTS}/${runningTournament.id}`, {
        body: detailResponse({
          id: runningTournament.id,
          status: 'RUNNING',
          registeredPlayers: 4,
          playersRemaining: 4
        })
      }).as('getRunningDetail');

      cy.intercept('POST', `${API_TOURNAMENTS}/${runningTournament.id}/tables/table-1/hand`, {
        statusCode: 200,
        body: {
          id: 'game-1',
          players: [],
          communityCards: [],
          pot: 30,
          currentPlayerIndex: 0,
          phase: 'PRE_FLOP',
          dealerPosition: 0,
          currentBet: 20
        }
      }).as('startHand');

      cy.visit(`/tournaments/${runningTournament.id}`);
      cy.wait('@getRunningDetail');
      cy.get('[data-cy="go-to-table-btn"]').click();
      cy.url().should('include', `/tournaments/${runningTournament.id}/play`);
      cy.get('[data-cy="tournament-table"]', { timeout: 15000 }).should('be.visible');
    });
  });
});
