import { TestBed, fakeAsync, tick, flush } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import {
  TournamentStore,
  TournamentListViewModel,
  TournamentLobbyViewModel,
  TournamentTableViewModel
} from './tournament.store';
import {
  Tournament,
  TournamentListItem,
  TournamentTable,
  TournamentPlayer,
  BlindLevel,
  DEFAULT_BLIND_LEVELS,
  DEFAULT_PRIZE_STRUCTURE,
  calculateTimeRemaining,
  formatTimeRemaining,
  getNextBlindLevel
} from '../model/tournament';
import { TournamentMessage, WebSocketService } from '../services/websocket.service';
import { AuthService } from '../services/auth.service';
import { TournamentDetailApi } from '../model/tournament-detail.mapper';
import { Subject, firstValueFrom, of } from 'rxjs';
import { environment } from '../../environments/environment';

describe('TournamentStore', () => {
  let store: TournamentStore;
  let httpMock: HttpTestingController;
  let wsServiceMock: jasmine.SpyObj<WebSocketService>;
  let authServiceMock: jasmine.SpyObj<AuthService>;
  const wsUpdates$ = new Subject<TournamentMessage>();
  let wsCurrentTournamentId: string | null = null;
  const apiUrl = `${environment.apiUrl}/v1/tournaments`;




  const createMockBlindLevel = (overrides: Partial<BlindLevel> = {}): BlindLevel => ({
    level: overrides.level ?? 1,
    smallBlind: overrides.smallBlind ?? 25,
    bigBlind: overrides.bigBlind ?? 50,
    ante: overrides.ante ?? 0,
    durationMinutes: overrides.durationMinutes ?? 15
  });

  const createMockTournamentPlayer = (overrides: Partial<TournamentPlayer> = {}): TournamentPlayer => ({
    id: overrides.id ?? 'player-1',
    name: overrides.name ?? 'TestPlayer',
    chips: overrides.chips ?? 10000,
    betAmount: overrides.betAmount ?? 0,
    totalBetInRound: overrides.totalBetInRound ?? 0,
    folded: overrides.folded ?? false,
    isBot: overrides.isBot ?? false,
    isAllIn: overrides.isAllIn ?? false,
    hasActed: overrides.hasActed ?? false,
    seatPosition: overrides.seatPosition ?? 0,
    hand: overrides.hand ?? [],
    rank: overrides.rank,
    finishPosition: overrides.finishPosition,
    prizeMoney: overrides.prizeMoney,
    tablesPlayed: overrides.tablesPlayed ?? 1,
    handsWon: overrides.handsWon ?? 0,
    biggestPot: overrides.biggestPot ?? 0,
    knockouts: overrides.knockouts ?? 0,
    isEliminated: overrides.isEliminated ?? false,
    eliminatedBy: overrides.eliminatedBy,
    eliminatedAtLevel: overrides.eliminatedAtLevel,
    canAct: function() {
      return !this.folded && !this.isAllIn && this.chips > 0;
    },
    getDisplayName: function() {
      if (this.name.startsWith('Bot')) {
        return this.name.substring(3) || 'Bot';
      }
      return this.name || 'Anonymous';
    },
    isHuman: function() {
      return !this.isBot && !this.name.startsWith('Bot');
    },
    getStatusText: function() {
      if (this.folded) return 'Folded';
      if (this.isAllIn) return 'All-In';
      if (this.chips === 0) return 'Out';
      return '';
    }
  });

  const createMockTournamentTable = (overrides: Partial<TournamentTable> = {}): TournamentTable => ({
    id: overrides.id ?? 'table-1',
    tableNumber: overrides.tableNumber ?? 1,
    players: overrides.players ?? [
      createMockTournamentPlayer({ id: 'p1', name: 'Player1' }),
      createMockTournamentPlayer({ id: 'p2', name: 'Player2', isBot: true })
    ],
    currentHandNumber: overrides.currentHandNumber ?? 1,
    dealerPosition: overrides.dealerPosition ?? 0,
    isActive: overrides.isActive ?? true
  });

  const createMockTournament = (overrides: Partial<Tournament> = {}): Tournament => ({
    id: overrides.id ?? 'tournament-123',
    name: overrides.name ?? 'Test Tournament',
    status: overrides.status ?? 'REGISTERING',
    config: overrides.config ?? {
      name: 'Test Tournament',
      buyIn: 100,
      startingChips: 10000,
      maxPlayers: 9,
      minPlayers: 2,
      blindLevels: DEFAULT_BLIND_LEVELS,
      levelDurationMinutes: 15,
      breakAfterLevels: 3,
      breakDurationMinutes: 5,
      prizeStructure: DEFAULT_PRIZE_STRUCTURE,
      lateRegistrationLevels: 3,
      rebuyAllowed: false,
      rebuyLevels: 0,
      addOnAllowed: false
    },
    currentLevel: overrides.currentLevel ?? 1,
    currentBlinds: overrides.currentBlinds ?? createMockBlindLevel(),
    levelStartTime: overrides.levelStartTime ?? Date.now(),
    levelEndTime: overrides.levelEndTime ?? Date.now() + 15 * 60 * 1000,
    nextBreakAtLevel: overrides.nextBreakAtLevel ?? 3,
    registeredPlayers: overrides.registeredPlayers ?? [
      createMockTournamentPlayer({ id: 'human-1', name: 'Human', isBot: false }),
      createMockTournamentPlayer({ id: 'bot-1', name: 'Bot1', isBot: true }),
      createMockTournamentPlayer({ id: 'bot-2', name: 'Bot2', isBot: true })
    ],
    remainingPlayers: overrides.remainingPlayers ?? 3,
    totalPlayers: overrides.totalPlayers ?? 3,
    tables: overrides.tables ?? [createMockTournamentTable()],
    averageStack: overrides.averageStack ?? 10000,
    largestStack: overrides.largestStack ?? 12000,
    smallestStack: overrides.smallestStack ?? 8000,
    totalChipsInPlay: overrides.totalChipsInPlay ?? 30000,
    handsPlayed: overrides.handsPlayed ?? 0,
    prizePool: overrides.prizePool ?? 300,
    startTime: overrides.startTime,
    endTime: overrides.endTime,
    createdAt: overrides.createdAt ?? Date.now(),
    updatedAt: overrides.updatedAt ?? Date.now()
  });

  const createMockTournamentDetailApi = (
    overrides: Partial<TournamentDetailApi> = {}
  ): TournamentDetailApi => ({
    id: overrides.id ?? 't1',
    name: overrides.name ?? 'Test Tournament',
    status: overrides.status ?? 'REGISTERING',
    registeredPlayers: overrides.registeredPlayers ?? 3,
    playersRemaining: overrides.playersRemaining ?? 3,
    minPlayers: overrides.minPlayers ?? 2,
    maxPlayers: overrides.maxPlayers ?? 9,
    currentLevel: overrides.currentLevel ?? 1,
    currentBlinds: overrides.currentBlinds ?? { level: 1, smallBlind: 10, bigBlind: 20, ante: 0 },
    nextBlinds: overrides.nextBlinds ?? null,
    secondsToNextLevel: overrides.secondsToNextLevel ?? 300,
    startingChips: overrides.startingChips ?? 1500,
    averageStack: overrides.averageStack ?? 1500,
    buyIn: overrides.buyIn ?? 100,
    prizePool: overrides.prizePool ?? 300,
    players: overrides.players ?? [
      {
        rank: 1,
        playerId: 'new-player',
        playerName: 'TestPlayer',
        chips: 1500,
        status: 'REGISTERED'
      }
    ],
    tables: overrides.tables ?? []
  });

  const createMockTournamentListItem = (overrides: Partial<TournamentListItem> = {}): TournamentListItem => ({
    id: overrides.id ?? 'tournament-123',
    name: overrides.name ?? 'Test Tournament',
    status: overrides.status ?? 'REGISTERING',
    buyIn: overrides.buyIn ?? 100,
    startingChips: overrides.startingChips ?? 10000,
    currentLevel: overrides.currentLevel ?? 1,
    registeredCount: overrides.registeredCount ?? 5,
    maxPlayers: overrides.maxPlayers ?? 9,
    prizePool: overrides.prizePool ?? 500,
    startTime: overrides.startTime,
    smallBlind: overrides.smallBlind ?? 25,
    bigBlind: overrides.bigBlind ?? 50
  });




  beforeEach(() => {
    wsServiceMock = jasmine.createSpyObj('WebSocketService', [
      'connect',
      'disconnect',
      'subscribeToGame',
      'subscribeToTournament',
      'updateTournamentTableSubscription',
      'unsubscribeFromTournament',
      'isConnected'
    ], {
      tournamentUpdates$: wsUpdates$.asObservable(),
      connectionStatus$: of(false)
    });
    wsServiceMock.isConnected.and.returnValue(false);
    wsCurrentTournamentId = null;
    Object.defineProperty(wsServiceMock, 'currentTournamentId', {
      configurable: true,
      value: () => wsCurrentTournamentId
    });

    authServiceMock = jasmine.createSpyObj('AuthService', ['getCurrentUserValue']);
    authServiceMock.getCurrentUserValue.and.returnValue({
      id: 'new-player',
      username: 'TestPlayer',
      email: 'test@test.com',
      firstName: 'Test',
      lastName: 'Player',
      roles: ['USER'],
      totalGamesPlayed: 0,
      totalWinnings: 0
    });

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        TournamentStore,
        { provide: WebSocketService, useValue: wsServiceMock },
        { provide: AuthService, useValue: authServiceMock }
      ]
    });

    store = TestBed.inject(TournamentStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    store.ngOnDestroy();
  });




  describe('Initialization', () => {
    it('should be created', () => {
      expect(store).toBeTruthy();
    });

    it('should initialize with empty tournaments array', (done) => {
      store.tournaments$.subscribe(tournaments => {
        expect(tournaments).toEqual([]);
        done();
      });
    });

    it('should initialize with null activeTournament', (done) => {
      store.activeTournament$.subscribe(tournament => {
        expect(tournament).toBeNull();
        done();
      });
    });

    it('should initialize with null myTable', (done) => {
      store.myTable$.subscribe(table => {
        expect(table).toBeNull();
        done();
      });
    });

    it('should initialize with null myPlayer', (done) => {
      store.myPlayer$.subscribe(player => {
        expect(player).toBeNull();
        done();
      });
    });

    it('should start with isLoading false', (done) => {
      store.isLoading$.subscribe(loading => {
        expect(loading).toBe(false);
        done();
      });
    });

    it('should start with disconnected connectionStatus', (done) => {
      store.connectionStatus$.subscribe(status => {
        expect(status).toBe('disconnected');
        done();
      });
    });
  });




  describe('Updaters', () => {
    describe('setTournaments', () => {
      it('should update tournaments list', (done) => {
        const tournaments = [
          createMockTournamentListItem({ id: 't1' }),
          createMockTournamentListItem({ id: 't2' })
        ];

        store.setTournaments(tournaments);

        store.tournaments$.subscribe(result => {
          expect(result.length).toBe(2);
          expect(result[0].id).toBe('t1');
          done();
        });
      });

      it('should set isLoading to false', (done) => {
        store.setLoading(true);
        store.setTournaments([]);

        store.isLoading$.subscribe(loading => {
          expect(loading).toBe(false);
          done();
        });
      });
    });

    describe('setActiveTournament', () => {
      it('should update active tournament', (done) => {
        const tournament = createMockTournament();

        store.setActiveTournament(tournament);

        store.activeTournament$.subscribe(result => {
          expect(result?.id).toBe('tournament-123');
          done();
        });
      });
    });

    describe('setMyPlayer', () => {
      it('should update my player', (done) => {
        const player = createMockTournamentPlayer({ id: 'me', name: 'MyPlayer' });

        store.setMyPlayer(player);

        store.myPlayer$.subscribe(result => {
          expect(result?.name).toBe('MyPlayer');
          done();
        });
      });
    });

    describe('setMyTable', () => {
      it('should update my table', (done) => {
        const table = createMockTournamentTable({ tableNumber: 5 });

        store.setMyTable(table);

        store.myTable$.subscribe(result => {
          expect(result?.tableNumber).toBe(5);
          done();
        });
      });
    });

    describe('setError', () => {
      it('should set error message', (done) => {
        store.setError('Test error');

        store.error$.subscribe(error => {
          expect(error).toBe('Test error');
          done();
        });
      });

      it('should set isLoading to false', (done) => {
        store.setLoading(true);
        store.setError('Error');

        store.isLoading$.subscribe(loading => {
          expect(loading).toBe(false);
          done();
        });
      });

      it('should set isRegistering to false', (done) => {
        store.setRegistering(true);
        store.setError('Error');

        store.isRegistering$.subscribe(registering => {
          expect(registering).toBe(false);
          done();
        });
      });
    });

    describe('clearError', () => {
      it('should clear error', (done) => {
        store.setError('Some error');
        store.clearError();

        store.error$.subscribe(error => {
          expect(error).toBeNull();
          done();
        });
      });
    });

    describe('reset', () => {
      it('should reset to initial state', (done) => {
        store.setTournaments([createMockTournamentListItem()]);
        store.setActiveTournament(createMockTournament());
        store.setError('Error');
        store.reset();

        store.tournaments$.subscribe(tournaments => {
          expect(tournaments).toEqual([]);
          done();
        });
      });
    });
  });




  describe('Derived Selectors', () => {
    describe('openTournaments$', () => {
      it('should filter only registering tournaments', (done) => {
        const tournaments = [
          createMockTournamentListItem({ id: 't1', status: 'REGISTERING' }),
          createMockTournamentListItem({ id: 't2', status: 'RUNNING' }),
          createMockTournamentListItem({ id: 't3', status: 'REGISTERING' })
        ];

        store.setTournaments(tournaments);

        store.openTournaments$.subscribe(result => {
          expect(result.length).toBe(2);
          expect(result.every(t => t.status === 'REGISTERING')).toBe(true);
          done();
        });
      });
    });

    describe('runningTournaments$', () => {
      it('should filter running and final table tournaments', (done) => {
        const tournaments = [
          createMockTournamentListItem({ id: 't1', status: 'REGISTERING' }),
          createMockTournamentListItem({ id: 't2', status: 'RUNNING' }),
          createMockTournamentListItem({ id: 't3', status: 'FINAL_TABLE' })
        ];

        store.setTournaments(tournaments);

        store.runningTournaments$.subscribe(result => {
          expect(result.length).toBe(2);
          done();
        });
      });
    });

    describe('currentBlinds$', () => {
      it('should return current blinds from active tournament', (done) => {
        const blinds = createMockBlindLevel({ smallBlind: 100, bigBlind: 200 });
        const tournament = createMockTournament({ currentBlinds: blinds });

        store.setActiveTournament(tournament);

        store.currentBlinds$.subscribe(result => {
          expect(result?.smallBlind).toBe(100);
          expect(result?.bigBlind).toBe(200);
          done();
        });
      });

      it('should return null when no active tournament', (done) => {
        store.currentBlinds$.subscribe(result => {
          expect(result).toBeNull();
          done();
        });
      });
    });

    describe('nextBlinds$', () => {
      it('should return next blind level', (done) => {
        const tournament = createMockTournament({ currentLevel: 1 });

        store.setActiveTournament(tournament);

        store.nextBlinds$.subscribe(result => {
          expect(result?.level).toBe(2);
          done();
        });
      });
    });

    describe('remainingPlayers$', () => {
      it('should return remaining player count', (done) => {
        const tournament = createMockTournament({ remainingPlayers: 42 });

        store.setActiveTournament(tournament);

        store.remainingPlayers$.subscribe(result => {
          expect(result).toBe(42);
          done();
        });
      });
    });

    describe('averageStack$', () => {
      it('should return average stack', (done) => {
        const tournament = createMockTournament({ averageStack: 15000 });

        store.setActiveTournament(tournament);

        store.averageStack$.subscribe(result => {
          expect(result).toBe(15000);
          done();
        });
      });
    });

    describe('isOnBreak$', () => {
      it('should return true when tournament is paused', (done) => {
        const tournament = createMockTournament({ status: 'PAUSED' });

        store.setActiveTournament(tournament);

        store.isOnBreak$.subscribe(result => {
          expect(result).toBe(true);
          done();
        });
      });

      it('should return false when tournament is running', (done) => {
        const tournament = createMockTournament({ status: 'RUNNING' });

        store.setActiveTournament(tournament);

        store.isOnBreak$.subscribe(result => {
          expect(result).toBe(false);
          done();
        });
      });
    });

    describe('isFinalTable$', () => {
      it('should return true at final table', (done) => {
        const tournament = createMockTournament({ status: 'FINAL_TABLE' });

        store.setActiveTournament(tournament);

        store.isFinalTable$.subscribe(result => {
          expect(result).toBe(true);
          done();
        });
      });
    });

    describe('isEliminated$', () => {
      it('should return true when player is eliminated', (done) => {
        const player = createMockTournamentPlayer({ isEliminated: true });

        store.setMyPlayer(player);

        store.isEliminated$.subscribe(result => {
          expect(result).toBe(true);
          done();
        });
      });
    });

    describe('myRank$', () => {
      it('should calculate player rank by chip count', (done) => {
        const players = [
          createMockTournamentPlayer({ id: 'p1', chips: 20000 }),
          createMockTournamentPlayer({ id: 'me', chips: 15000 }),
          createMockTournamentPlayer({ id: 'p3', chips: 10000 })
        ];
        const tournament = createMockTournament({ registeredPlayers: players });
        const myPlayer = createMockTournamentPlayer({ id: 'me', chips: 15000 });

        store.setActiveTournament(tournament);
        store.setMyPlayer(myPlayer);

        store.myRank$.subscribe(result => {
          expect(result).toBe(2);
          done();
        });
      });

      it('should exclude eliminated players from ranking', (done) => {
        const players = [
          createMockTournamentPlayer({ id: 'p1', chips: 20000, isEliminated: true }),
          createMockTournamentPlayer({ id: 'me', chips: 15000 }),
          createMockTournamentPlayer({ id: 'p3', chips: 10000 })
        ];
        const tournament = createMockTournament({ registeredPlayers: players });
        const myPlayer = createMockTournamentPlayer({ id: 'me', chips: 15000 });

        store.setActiveTournament(tournament);
        store.setMyPlayer(myPlayer);

        store.myRank$.subscribe(result => {
          expect(result).toBe(1);
          done();
        });
      });
    });
  });




  describe('View Models', () => {
    describe('tournamentListVm$', () => {
      it('should compose correct view model', (done) => {
        const tournaments = [
          createMockTournamentListItem({ status: 'REGISTERING' }),
          createMockTournamentListItem({ status: 'RUNNING' })
        ];

        store.setTournaments(tournaments);

        store.tournamentListVm$.subscribe((vm: TournamentListViewModel) => {
          expect(vm.tournaments.length).toBe(2);
          expect(vm.openTournaments.length).toBe(1);
          expect(vm.runningTournaments.length).toBe(1);
          expect(vm.isLoading).toBe(false);
          expect(vm.error).toBeNull();
          done();
        });
      });
    });

    describe('tournamentLobbyVm$', () => {
      it('should indicate registration is possible', (done) => {
        const tournament = createMockTournament({
          status: 'REGISTERING',
          registeredPlayers: [createMockTournamentPlayer()]
        });

        store.setActiveTournament(tournament);

        store.tournamentLobbyVm$.subscribe((vm: TournamentLobbyViewModel) => {
          expect(vm.canRegister).toBe(true);
          expect(vm.isRegistered).toBe(false);
          expect(vm.spotsRemaining).toBe(8);
          done();
        });
      });

      it('should indicate player is registered', (done) => {
        const tournament = createMockTournament();
        const myPlayer = createMockTournamentPlayer({ id: 'me' });

        store.setActiveTournament(tournament);
        store.setMyPlayer(myPlayer);

        store.tournamentLobbyVm$.subscribe((vm: TournamentLobbyViewModel) => {
          expect(vm.isRegistered).toBe(true);
          done();
        });
      });

      it('should not allow registration when full', (done) => {
        const players = Array(9).fill(null).map((_, i) =>
          createMockTournamentPlayer({ id: `p${i}` })
        );
        const tournament = createMockTournament({
          status: 'REGISTERING',
          registeredPlayers: players
        });

        store.setActiveTournament(tournament);

        store.tournamentLobbyVm$.subscribe((vm: TournamentLobbyViewModel) => {
          expect(vm.canRegister).toBe(false);
          expect(vm.spotsRemaining).toBe(0);
          done();
        });
      });
    });

    describe('tournamentTableVm$', () => {
      it('should compose complete table view model', (done) => {
        const tournament = createMockTournament({
          status: 'RUNNING',
          remainingPlayers: 6,
          averageStack: 12000,
          currentLevel: 3
        });
        const table = createMockTournamentTable();
        const myPlayer = createMockTournamentPlayer({ id: 'me' });

        store.setActiveTournament(tournament);
        store.setMyTable(table);
        store.setMyPlayer(myPlayer);

        store.tournamentTableVm$.subscribe((vm: TournamentTableViewModel) => {
          expect(vm.tournament).toBeTruthy();
          expect(vm.table).toBeTruthy();
          expect(vm.myPlayer).toBeTruthy();
          expect(vm.remainingPlayers).toBe(6);
          expect(vm.averageStack).toBe(12000);
          expect(vm.isOnBreak).toBe(false);
          expect(vm.isFinalTable).toBe(false);
          expect(vm.isEliminated).toBe(false);
          done();
        });
      });
    });
  });




  describe('Effects', () => {
    describe('loadTournaments', () => {
      it('should load tournaments from API', fakeAsync(() => {
        store.loadTournaments();
        tick();

        const req = httpMock.expectOne(`${apiUrl}`);
        expect(req.request.method).toBe('GET');
        req.flush([
          {
            id: 't1',
            name: 'T1',
            status: 'REGISTERING',
            registeredPlayers: 5,
            maxPlayers: 9,
            buyIn: 100,
            prizePool: 500,
            currentLevel: 1
          },
          {
            id: 't2',
            name: 'T2',
            status: 'RUNNING',
            registeredPlayers: 9,
            maxPlayers: 9,
            buyIn: 100,
            prizePool: 900,
            currentLevel: 2
          }
        ]);

        tick();

        store.tournaments$.subscribe(result => {
          expect(result.length).toBe(2);
          expect(result[0].registeredCount).toBe(5);
        });

        flush();
      }));

      it('should handle API error', fakeAsync(() => {
        store.loadTournaments();
        tick();

        const req = httpMock.expectOne(`${apiUrl}`);
        req.flush('Error', { status: 500, statusText: 'Server Error' });

        tick();

        store.error$.subscribe(error => {
          expect(error).toBeTruthy();
        });

        flush();
      }));

      it('should set loading state during request', fakeAsync(() => {
        const loadingStates: boolean[] = [];

        store.isLoading$.subscribe(loading => {
          loadingStates.push(loading);
        });

        store.loadTournaments();
        tick();

        expect(loadingStates).toContain(true);

        const req = httpMock.expectOne(`${apiUrl}`);
        req.flush([]);

        tick();
        flush();
      }));
    });

    describe('requestRebuy', () => {
      it('should post a rebuy for my player and reload the tournament', fakeAsync(() => {
        store.setMyPlayer(createMockTournamentPlayer({ id: 'me', name: 'MyPlayer' }));

        store.requestRebuy('test-id');
        tick();

        const rebuyReq = httpMock.expectOne(`${apiUrl}/test-id/rebuy`);
        expect(rebuyReq.request.method).toBe('POST');
        expect(rebuyReq.request.body).toEqual({ playerId: 'me' });
        rebuyReq.flush({
          playerId: 'me', playerName: 'MyPlayer', newChipCount: 1500,
          rebuysUsed: 1, rebuysRemaining: 2, canRebuyAgain: true
        });
        tick();

        const reload = httpMock.expectOne(`${apiUrl}/test-id`);
        expect(reload.request.method).toBe('GET');
        reload.flush(createMockTournamentDetailApi({ id: 'test-id' }));

        flush();
      }));

      it('should not call the rebuy endpoint when there is no seated player', fakeAsync(() => {
        store.setMyPlayer(null);

        store.requestRebuy('test-id');
        tick();

        httpMock.expectNone(`${apiUrl}/test-id/rebuy`);
        flush();
      }));
    });

    describe('loadTournament', () => {
      it('should load specific tournament by ID', fakeAsync(() => {
        store.loadTournament('test-id');
        tick();

        const req = httpMock.expectOne(`${apiUrl}/test-id`);
        expect(req.request.method).toBe('GET');
        req.flush(createMockTournamentDetailApi({ id: 'test-id' }));

        tick();

        store.activeTournament$.subscribe(result => {
          expect(result?.id).toBe('test-id');
        });

        flush();
      }));

      it('should set myPlayer when human player found', fakeAsync(() => {
        const humanPlayer = createMockTournamentPlayer({ id: 'human', isBot: false });
        const tournament = createMockTournament({
          registeredPlayers: [humanPlayer]
        });

        store.loadTournament('test-id');
        tick();

        const req = httpMock.expectOne(`${apiUrl}/test-id`);
        req.flush({
          id: tournament.id,
          name: tournament.name,
          status: tournament.status,
          registeredPlayers: tournament.totalPlayers,
          playersRemaining: tournament.remainingPlayers,
          minPlayers: tournament.config.minPlayers,
          maxPlayers: tournament.config.maxPlayers,
          currentLevel: tournament.currentLevel,
          currentBlinds: {
            level: tournament.currentBlinds.level,
            smallBlind: tournament.currentBlinds.smallBlind,
            bigBlind: tournament.currentBlinds.bigBlind,
            ante: tournament.currentBlinds.ante
          },
          nextBlinds: null,
          secondsToNextLevel: 900,
          startingChips: tournament.config.startingChips,
          averageStack: tournament.averageStack,
          buyIn: tournament.config.buyIn,
          prizePool: tournament.prizePool,
          players: tournament.registeredPlayers.map((p, i) => ({
            rank: i + 1,
            playerId: p.id,
            playerName: p.name,
            chips: p.chips ?? 0,
            status: p.isEliminated ? 'ELIMINATED' : 'PLAYING'
          }))
        });

        tick();

        store.myPlayer$.subscribe(player => {
          expect(player?.id).toBe('human');
        });

        flush();
      }));

      it('should set myTable when human is seated at a table with players', fakeAsync(() => {
        const humanPlayer = createMockTournamentPlayer({ id: 'human', isBot: false });
        const table = createMockTournamentTable({
          id: 'table-42',
          tableNumber: 2,
          players: [
            humanPlayer,
            createMockTournamentPlayer({ id: 'bot-1', isBot: true })
          ]
        });
        const tournament = createMockTournament({
          registeredPlayers: [humanPlayer],
          tables: [table]
        });

        store.loadTournament('test-id');
        tick();

        const req = httpMock.expectOne(`${apiUrl}/test-id`);
        req.flush({
          id: tournament.id,
          name: tournament.name,
          status: tournament.status,
          registeredPlayers: tournament.totalPlayers,
          playersRemaining: tournament.remainingPlayers,
          minPlayers: tournament.config.minPlayers,
          maxPlayers: tournament.config.maxPlayers,
          currentLevel: tournament.currentLevel,
          currentBlinds: {
            level: tournament.currentBlinds.level,
            smallBlind: tournament.currentBlinds.smallBlind,
            bigBlind: tournament.currentBlinds.bigBlind,
            ante: tournament.currentBlinds.ante
          },
          nextBlinds: null,
          secondsToNextLevel: 900,
          startingChips: tournament.config.startingChips,
          averageStack: tournament.averageStack,
          buyIn: tournament.config.buyIn,
          prizePool: tournament.prizePool,
          players: tournament.registeredPlayers.map((p, i) => ({
            rank: i + 1,
            playerId: p.id,
            playerName: p.name,
            chips: p.chips ?? 0,
            status: 'PLAYING'
          })),
          tables: tournament.tables.map(t => ({
            id: t.id,
            tableNumber: t.tableNumber,
            playerCount: t.players.length,
            isFinalTable: false,
            currentGameId: t.currentGameId ?? null,
            players: t.players.map(p => ({
              id: p.id,
              name: p.name,
              chips: p.chips ?? 0,
              isBot: p.isBot
            }))
          }))
        });

        tick();

        store.myTable$.subscribe(tableResult => {
          expect(tableResult?.id).toBe('table-42');
          expect(tableResult?.tableNumber).toBe(2);
        });

        flush();
      }));

      it('should leave myTable null when tables have no players list', fakeAsync(() => {
        const humanPlayer = createMockTournamentPlayer({ id: 'human', isBot: false });
        const tournament = createMockTournament({
          registeredPlayers: [humanPlayer],
          tables: [{ ...createMockTournamentTable(), players: [] }]
        });

        store.loadTournament('test-id');
        tick();

        const req = httpMock.expectOne(`${apiUrl}/test-id`);
        req.flush(tournament);

        tick();

        store.myTable$.subscribe(tableResult => {
          expect(tableResult).toBeNull();
        });

        flush();
      }));
    });

    describe('ensureTableHand', () => {
      it('should POST hand and store game in tableHandGame', fakeAsync(() => {
        const mockGame = {
          id: 'game-99',
          currentPot: 75,
          players: [],
          communityCards: [],
          phase: 'PRE_FLOP',
          currentBet: 50,
          currentPlayerIndex: 0,
          dealerPosition: 0,
          minRaiseAmount: 50,
          bigBlind: 50,
          smallBlind: 25,
          isFinished: false,
          handNumber: 1
        };

        store.ensureTableHand({ tournamentId: 't-1', tableId: 'table-7' });
        tick();

        const req = httpMock.expectOne(`${apiUrl}/t-1/tables/table-7/hand`);
        expect(req.request.method).toBe('POST');
        req.flush(mockGame);

        tick();

        store.tableHandGame$.subscribe(game => {
          expect(game?.id).toBe('game-99');
        });

        flush();
      }));
    });

    describe('registerForTournament', () => {
      it('should register player for tournament', fakeAsync(() => {
        store.registerForTournament({ tournamentId: 't1', playerName: 'TestPlayer' });
        tick();

        const registerReq = httpMock.expectOne(`${apiUrl}/t1/register`);
        expect(registerReq.request.method).toBe('POST');
        expect(registerReq.request.body).toEqual({
          playerId: 'new-player',
          playerName: 'TestPlayer'
        });
        registerReq.flush(createMockTournamentDetailApi());

        tick();

        store.myPlayer$.subscribe(player => {
          expect(player?.id).toBe('new-player');
        });

        flush();
      }));

      it('should set registering state', fakeAsync(() => {
        const registeringStates: boolean[] = [];

        store.isRegistering$.subscribe(registering => {
          registeringStates.push(registering);
        });

        store.registerForTournament({ tournamentId: 't1', playerName: 'Test' });
        tick();

        expect(registeringStates).toContain(true);

        const req = httpMock.expectOne(`${apiUrl}/t1/register`);
        req.flush(createMockTournamentDetailApi());

        tick();
        flush();
      }));
    });

    describe('unregisterFromTournament', () => {
      it('should unregister player from tournament', fakeAsync(() => {
        const myPlayer = createMockTournamentPlayer({ id: 'my-id' });
        store.setMyPlayer(myPlayer);

        store.unregisterFromTournament('t1');
        tick();

        const req = httpMock.expectOne(`${apiUrl}/t1/unregister`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ playerId: 'my-id' });
        req.flush('OK');

        tick();


        const tournamentWithOnlyBots = createMockTournament({
          registeredPlayers: [
            createMockTournamentPlayer({ id: 'bot-1', name: 'Bot1', isBot: true }),
            createMockTournamentPlayer({ id: 'bot-2', name: 'Bot2', isBot: true })
          ]
        });
        httpMock.expectOne(`${apiUrl}/t1`).flush(tournamentWithOnlyBots);

        tick();

        store.myPlayer$.subscribe(player => {
          expect(player).toBeNull();
        });

        flush();
      }));

      it('should not make request if no player set', fakeAsync(() => {
        store.unregisterFromTournament('t1');
        tick();

        httpMock.expectNone(`${apiUrl}/t1/unregister`);

        flush();
      }));
    });
  });




  describe('Helper Methods', () => {
    describe('getMyPlayer', () => {
      it('should return current player', () => {
        const player = createMockTournamentPlayer({ name: 'Test' });
        store.setMyPlayer(player);

        expect(store.getMyPlayer()?.name).toBe('Test');
      });

      it('should return null when no player', () => {
        expect(store.getMyPlayer()).toBeNull();
      });
    });

    describe('getCurrentTournamentId', () => {
      it('should return tournament ID', () => {
        const tournament = createMockTournament({ id: 'test-123' });
        store.setActiveTournament(tournament);

        expect(store.getCurrentTournamentId()).toBe('test-123');
      });

      it('should return null when no tournament', () => {
        expect(store.getCurrentTournamentId()).toBeNull();
      });
    });

    describe('isPlayerRegistered', () => {
      it('should return true when player is set', () => {
        store.setMyPlayer(createMockTournamentPlayer());

        expect(store.isPlayerRegistered()).toBe(true);
      });

      it('should return false when no player', () => {
        expect(store.isPlayerRegistered()).toBe(false);
      });
    });

    describe('WebSocket incremental updates', () => {
      beforeEach(() => {
        wsServiceMock.isConnected.and.returnValue(true);
      });

      it('patches elimination without HTTP refresh', fakeAsync(async () => {
        const tournament = createMockTournament({
          status: 'RUNNING',
          registeredPlayers: [
            createMockTournamentPlayer({ id: 'human-1', name: 'Human' }),
            createMockTournamentPlayer({ id: 'bot-1', name: 'Bot1', isBot: true })
          ],
          tables: [
            createMockTournamentTable({
              players: [
                createMockTournamentPlayer({ id: 'human-1', name: 'Human' }),
                createMockTournamentPlayer({ id: 'bot-1', name: 'Bot1', isBot: true })
              ]
            })
          ],
          remainingPlayers: 2
        });
        store.setActiveTournament(tournament);
        store.setMyPlayer(tournament.registeredPlayers[0]);
        store.setMyTable(tournament.tables[0]);

        wsUpdates$.next({
          type: 'PLAYER_ELIMINATED',
          tournamentId: tournament.id,
          data: {
            playerId: 'bot-1',
            playerName: 'Bot1',
            finishPosition: 2,
            prize: 0,
            playersRemaining: 1
          }
        });
        tick(300);

        const active = await firstValueFrom(store.activeTournament$);
        const lastUpdate = await firstValueFrom(store.lastUpdate$);
        expect(active?.remainingPlayers).toBe(1);
        expect(active?.registeredPlayers.find(p => p.id === 'bot-1')?.isEliminated).toBe(true);
        expect(lastUpdate?.type).toBe('PLAYER_ELIMINATED');
        httpMock.expectNone(`${apiUrl}/${tournament.id}`);
      }));

      it('patches table rebalance and resubscribes WS for self move', fakeAsync(async () => {
        wsCurrentTournamentId = 'tournament-123';
        const human = createMockTournamentPlayer({ id: 'human-1', name: 'Human' });
        const tournament = createMockTournament({
          status: 'RUNNING',
          tables: [
            createMockTournamentTable({
              id: 'table-1',
              tableNumber: 1,
              players: [human]
            }),
            createMockTournamentTable({
              id: 'table-2',
              tableNumber: 2,
              players: [createMockTournamentPlayer({ id: 'bot-1', name: 'Bot1', isBot: true })]
            })
          ]
        });
        store.setActiveTournament(tournament);
        store.setMyPlayer(human);
        store.setMyTable(tournament.tables[0]);

        wsUpdates$.next({
          type: 'TABLE_REBALANCED',
          tournamentId: tournament.id,
          data: {
            playerMoves: [{ playerId: 'human-1', fromTableId: 'table-1', toTableId: 'table-2' }],
            closedTableIds: ['table-1'],
            activeTableCount: 1,
            finalTableFormed: true,
            playersRemaining: 2
          }
        });
        tick(300);

        const active = await firstValueFrom(store.activeTournament$);
        const myTable = await firstValueFrom(store.myTable$);
        expect(active?.status).toBe('FINAL_TABLE');
        expect(myTable?.tableNumber).toBe(2);
        expect(wsServiceMock.updateTournamentTableSubscription).toHaveBeenCalledWith(
          tournament.id,
          2
        );
        httpMock.expectNone(`${apiUrl}/${tournament.id}`);
      }));
    });
  });
});





describe('Tournament Model Helpers', () => {
  describe('calculateTimeRemaining', () => {
    it('should calculate positive time remaining', () => {
      const futureTime = Date.now() + 60000;
      const result = calculateTimeRemaining(futureTime);

      expect(result).toBeGreaterThan(59000);
      expect(result).toBeLessThanOrEqual(60000);
    });

    it('should return 0 for past time', () => {
      const pastTime = Date.now() - 1000;
      const result = calculateTimeRemaining(pastTime);

      expect(result).toBe(0);
    });
  });

  describe('formatTimeRemaining', () => {
    it('should format minutes and seconds', () => {
      const result = formatTimeRemaining(125000);

      expect(result).toBe('02:05');
    });

    it('should handle zero', () => {
      const result = formatTimeRemaining(0);

      expect(result).toBe('00:00');
    });

    it('should handle large values', () => {
      const result = formatTimeRemaining(3661000);

      expect(result).toBe('61:01');
    });
  });

  describe('getNextBlindLevel', () => {
    it('should return next level', () => {
      const result = getNextBlindLevel(1, DEFAULT_BLIND_LEVELS);

      expect(result?.level).toBe(2);
      expect(result?.smallBlind).toBe(50);
    });

    it('should return null at max level', () => {
      const result = getNextBlindLevel(12, DEFAULT_BLIND_LEVELS);

      expect(result).toBeNull();
    });

    it('should return null for invalid level', () => {
      const result = getNextBlindLevel(100, DEFAULT_BLIND_LEVELS);

      expect(result).toBeNull();
    });
  });
});
