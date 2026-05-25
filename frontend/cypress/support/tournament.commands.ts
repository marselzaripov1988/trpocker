const DEFAULT_PASSWORD = 'E2eTest123!';

function apiBase(): string {
  return Cypress.env('API_URL') || 'http://localhost:8080/api';
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

function registerUser(username: string): Cypress.Chainable<{ token: string; username: string }> {
  const api = apiBase();
  const email = `${username}@cypress.local`;
  return cy.request({
    method: 'POST',
    url: `${api}/auth/register`,
    body: {
      username,
      email,
      password: DEFAULT_PASSWORD,
      firstName: 'E2E',
      lastName: 'Player'
    },
    failOnStatusCode: false
  }).then(registerRes => {
    return cy.request({
      method: 'POST',
      url: `${api}/auth/login`,
      body: { username, password: DEFAULT_PASSWORD }
    }).then(loginRes => {
      const token = loginRes.body.accessToken as string;
      expect(token).to.be.a('string');
      return { token, username };
    });
  });
}

function fetchProfile(token: string): Cypress.Chainable<{ id: string; username: string }> {
  return cy.request({
    method: 'GET',
    url: `${apiBase()}/v1/users/profile`,
    headers: authHeaders(token)
  }).then(res => ({
    id: res.body.id as string,
    username: res.body.username as string
  }));
}

Cypress.Commands.add('loginViaApi', (username?: string) => {
  const name = username ?? `cypress_${Date.now()}`;
  return registerUser(name).then(({ token, username: uname }) => {
    return fetchProfile(token).then(profile => {
      cy.setAuthSession(token, { ...profile, email: `${uname}@cypress.local` });
      return cy.wrap({ token, userId: profile.id, username: profile.username });
    });
  });
});

Cypress.Commands.add('setAuthSession', (token: string, user: { id: string; username: string; email?: string }) => {
  cy.window().then(win => {
    win.localStorage.setItem('access_token', token);
    win.localStorage.setItem('user', JSON.stringify({
      id: user.id,
      username: user.username,
      email: user.email ?? `${user.username}@cypress.local`,
      firstName: 'E2E',
      lastName: 'Player',
      roles: ['USER'],
      totalGamesPlayed: 0,
      totalWinnings: 0
    }));
  });
});

Cypress.Commands.add(
  'seedRunningSitAndGo',
  (options: { maxPlayers?: number } = {}) => {
    const maxPlayers = options.maxPlayers ?? 2;
    const runId = Date.now();

    return cy.loginViaApi(`cypress_p1_${runId}`).then(player1 => {
      const api = apiBase();
      return cy.request({
        method: 'POST',
        url: `${api}/v1/tournaments`,
        headers: authHeaders(player1.token),
        body: {
          name: `E2E SNG ${runId}`,
          type: 'SIT_AND_GO',
          startingChips: 1500,
          minPlayers: 2,
          maxPlayers,
          buyIn: 0,
          blindStructureType: 'TURBO'
        }
      }).then(createRes => {
        const tournamentId = createRes.body.id as string;
        expect(tournamentId).to.be.a('string');

        cy.request({
          method: 'POST',
          url: `${api}/v1/tournaments/${tournamentId}/register`,
          headers: authHeaders(player1.token),
          body: { playerId: player1.userId, playerName: player1.username }
        });

        return registerUser(`cypress_p2_${runId}`).then(player2 => {
          cy.request({
            method: 'POST',
            url: `${api}/v1/tournaments/${tournamentId}/register`,
            headers: authHeaders(player2.token),
            body: { playerId: player2.userId, playerName: player2.username }
          });

          const waitForRunning = (attempt = 0): Cypress.Chainable<string> => {
            if (attempt > 60) {
              throw new Error('Tournament did not reach RUNNING in time');
            }
            return cy.request({
              method: 'GET',
              url: `${api}/v1/tournaments/${tournamentId}`,
              headers: authHeaders(player1.token)
            }).then(detailRes => {
              const status = detailRes.body.status as string;
              if (status === 'RUNNING' || status === 'FINAL_TABLE') {
                return cy.wrap(tournamentId);
              }
              return cy.wait(500).then(() => waitForRunning(attempt + 1));
            });
          };

          return waitForRunning().then(id => {
            cy.setAuthSession(player1.token, {
              id: player1.userId,
              username: player1.username
            });
            return cy.wrap({
              tournamentId: id,
              token: player1.token,
              userId: player1.userId,
              username: player1.username
            });
          });
        });
      });
    });
  }
);

declare global {
  namespace Cypress {
    interface Chainable {
      loginViaApi(username?: string): Chainable<{
        token: string;
        userId: string;
        username: string;
      }>;
      setAuthSession(
        token: string,
        user: { id: string; username: string; email?: string }
      ): Chainable<void>;
      seedRunningSitAndGo(options?: { maxPlayers?: number }): Chainable<{
        tournamentId: string;
        token: string;
        userId: string;
        username: string;
      }>;
    }
  }
}

export {};
