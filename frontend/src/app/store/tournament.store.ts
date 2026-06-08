import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ComponentStore } from '@ngrx/component-store';
import { tapResponse } from '@ngrx/operators';
import { EMPTY, timer, of } from 'rxjs';
import {
  switchMap,
  tap,
  withLatestFrom,
  catchError,
  takeUntil,
  map,
  filter,
  distinctUntilChanged,
  shareReplay
} from 'rxjs/operators';
import { environment } from '../../environments/environment';
import {
  Tournament,
  TournamentListItem,
  TournamentTable,
  TournamentPlayer,
  TournamentUpdate,
  BlindLevel,
  calculateTimeRemaining,
  formatTimeRemaining,
  getNextBlindLevel
} from '../model/tournament';
import {
  blindLevelUpdateFromWs,
  mapTournamentDetailFromApi,
  TournamentDetailApi
} from '../model/tournament-detail.mapper';
import {
  mapTournamentListFromApi,
  TournamentSummaryApi
} from '../model/tournament-list.mapper';
import { AuthService } from '../services/auth.service';
import {
  finalTableReachedFromWs,
  playerEliminatedFromWs,
  tableRebalancedFromWs
} from '../model/tournament-ws.mapper';
import { Game } from '../model/game';
import { TournamentMessage, WebSocketService } from '../services/websocket.service';





export interface TournamentStoreState {
  tournaments: TournamentListItem[];
  activeTournament: Tournament | null;
  myTable: TournamentTable | null;
  myPlayer: TournamentPlayer | null;

  isLoading: boolean;
  isRegistering: boolean;
  error: string | null;

  connectionStatus: 'connected' | 'disconnected' | 'reconnecting';
  lastUpdate: TournamentUpdate | null;
  tableHandGame: Game | null;
}





export interface TournamentListViewModel {
  tournaments: TournamentListItem[];
  openTournaments: TournamentListItem[];
  runningTournaments: TournamentListItem[];
  isLoading: boolean;
  error: string | null;
}

export interface TournamentLobbyViewModel {
  tournament: Tournament | null;
  registeredPlayers: TournamentPlayer[];
  canRegister: boolean;
  isRegistered: boolean;
  isLoading: boolean;
  error: string | null;
  spotsRemaining: number;
  prizePool: number;
}

export interface TournamentTableViewModel {
  tournament: Tournament | null;
  table: TournamentTable | null;
  myPlayer: TournamentPlayer | null;


  currentBlinds: BlindLevel | null;
  nextBlinds: BlindLevel | null;
  timeToNextLevel: number;
  formattedTimeRemaining: string;


  remainingPlayers: number;
  averageStack: number;
  myRank: number | null;
  totalPlayers: number;


  isOnBreak: boolean;
  isFinalTable: boolean;
  isEliminated: boolean;

  isLoading: boolean;
  error: string | null;
}





const initialState: TournamentStoreState = {
  tournaments: [],
  activeTournament: null,
  myTable: null,
  myPlayer: null,

  isLoading: false,
  isRegistering: false,
  error: null,

  connectionStatus: 'disconnected',
  lastUpdate: null,
  tableHandGame: null
};





@Injectable()
export class TournamentStore extends ComponentStore<TournamentStoreState> {
  private readonly http = inject(HttpClient);
  private readonly wsService = inject(WebSocketService);
  private readonly authService = inject(AuthService);

  private readonly apiUrl = `${environment.apiUrl}/v1/tournaments`;
  private refreshDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private tournamentWsListenerReady = false;

  constructor() {
    super(initialState);
    this.initTournamentWebSocketListener();
  }





  readonly tournaments$ = this.select(state => state.tournaments);
  readonly activeTournament$ = this.select(state => state.activeTournament);
  readonly myTable$ = this.select(state => state.myTable);
  readonly myPlayer$ = this.select(state => state.myPlayer);
  readonly isLoading$ = this.select(state => state.isLoading);
  readonly isRegistering$ = this.select(state => state.isRegistering);
  readonly error$ = this.select(state => state.error);
  readonly connectionStatus$ = this.select(state => state.connectionStatus);
  readonly lastUpdate$ = this.select(state => state.lastUpdate);
  readonly tableHandGame$ = this.select(state => state.tableHandGame);





  readonly openTournaments$ = this.select(
    this.tournaments$,
    tournaments => tournaments.filter(t => t.status === 'REGISTERING')
  );

  readonly runningTournaments$ = this.select(
    this.tournaments$,
    tournaments => tournaments.filter(t =>
      t.status === 'RUNNING' || t.status === 'FINAL_TABLE'
    )
  );

  readonly currentBlinds$ = this.select(
    this.activeTournament$,
    tournament => tournament?.currentBlinds ?? null
  );

  readonly nextBlinds$ = this.select(
    this.activeTournament$,
    tournament => {
      if (!tournament) return null;
      if (tournament.nextBlinds) {
        return tournament.nextBlinds;
      }
      return getNextBlindLevel(tournament.currentLevel, tournament.config.blindLevels);
    }
  );

  readonly remainingPlayers$ = this.select(
    this.activeTournament$,
    tournament => tournament?.remainingPlayers ?? 0
  );

  readonly averageStack$ = this.select(
    this.activeTournament$,
    tournament => tournament?.averageStack ?? 0
  );

  readonly prizePool$ = this.select(
    this.activeTournament$,
    tournament => tournament?.prizePool ?? 0
  );

  readonly isOnBreak$ = this.select(
    this.activeTournament$,
    tournament => tournament?.status === 'PAUSED'
  );

  readonly isFinalTable$ = this.select(
    this.activeTournament$,
    tournament => tournament?.status === 'FINAL_TABLE'
  );

  readonly isEliminated$ = this.select(
    this.myPlayer$,
    player => player?.isEliminated ?? false
  );

  readonly myRank$ = this.select(
    this.activeTournament$,
    this.myPlayer$,
    (tournament, player) => {
      if (!tournament || !player) return null;

      const sortedPlayers = [...tournament.registeredPlayers]
        .filter(p => !p.isEliminated)
        .sort((a, b) => (b.chips ?? 0) - (a.chips ?? 0));

      const index = sortedPlayers.findIndex(p => p.id === player.id);
      return index >= 0 ? index + 1 : null;
    }
  );





  readonly tournamentListVm$ = this.select(
    this.tournaments$,
    this.openTournaments$,
    this.runningTournaments$,
    this.isLoading$,
    this.error$,
    (tournaments, openTournaments, runningTournaments, isLoading, error): TournamentListViewModel => ({
      tournaments,
      openTournaments,
      runningTournaments,
      isLoading,
      error
    })
  );

  readonly tournamentLobbyVm$ = this.select(
    this.activeTournament$,
    this.myPlayer$,
    this.isLoading$,
    this.isRegistering$,
    this.error$,
    (tournament, myPlayer, isLoading, isRegistering, error): TournamentLobbyViewModel => ({
      tournament,
      registeredPlayers: tournament?.registeredPlayers ?? [],
      canRegister: tournament?.status === 'REGISTERING' &&
                   (tournament.registeredPlayers.length < tournament.config.maxPlayers),
      isRegistered: myPlayer !== null && !myPlayer.isEliminated,
      isLoading: isLoading || isRegistering,
      error,
      spotsRemaining: tournament
        ? tournament.config.maxPlayers - tournament.registeredPlayers.length
        : 0,
      prizePool: tournament?.prizePool ?? 0
    })
  );

  readonly tournamentTableVm$ = this.select(
    this.activeTournament$,
    this.myTable$,
    this.myPlayer$,
    this.currentBlinds$,
    this.nextBlinds$,
    this.remainingPlayers$,
    this.averageStack$,
    this.myRank$,
    this.isOnBreak$,
    this.isFinalTable$,
    this.isEliminated$,
    this.isLoading$,
    this.error$,
    (
      tournament, table, myPlayer, currentBlinds, nextBlinds,
      remainingPlayers, averageStack, myRank, isOnBreak, isFinalTable,
      isEliminated, isLoading, error
    ): TournamentTableViewModel => {
      const timeToNextLevel = tournament
        ? calculateTimeRemaining(tournament.levelEndTime)
        : 0;

      return {
        tournament,
        table,
        myPlayer,
        currentBlinds,
        nextBlinds,
        timeToNextLevel,
        formattedTimeRemaining: formatTimeRemaining(timeToNextLevel),
        remainingPlayers,
        averageStack,
        myRank,
        totalPlayers: tournament?.totalPlayers ?? 0,
        isOnBreak,
        isFinalTable,
        isEliminated,
        isLoading,
        error
      };
    }
  );






  readonly timeRemaining$ = this.activeTournament$.pipe(
    switchMap(tournament => {
      if (!tournament) return of(0);

      return timer(0, 1000).pipe(
        map(() => calculateTimeRemaining(tournament.levelEndTime)),
        takeUntil(this.destroy$)
      );
    }),
    distinctUntilChanged(),
    shareReplay(1)
  );

  readonly formattedTimeRemaining$ = this.timeRemaining$.pipe(
    map(time => formatTimeRemaining(time))
  );





  readonly setLoading = this.updater((state, isLoading: boolean) => ({
    ...state,
    isLoading
  }));

  readonly setRegistering = this.updater((state, isRegistering: boolean) => ({
    ...state,
    isRegistering
  }));

  readonly setError = this.updater((state, error: string | null) => ({
    ...state,
    error,
    isLoading: false,
    isRegistering: false
  }));

  readonly clearError = this.updater(state => ({
    ...state,
    error: null
  }));

  readonly setTournaments = this.updater((state, tournaments: TournamentListItem[]) => ({
    ...state,
    tournaments,
    isLoading: false
  }));

  readonly setActiveTournament = this.updater((state, tournament: Tournament | null) => ({
    ...state,
    activeTournament: tournament,
    isLoading: false
  }));

  readonly setMyTable = this.updater((state, table: TournamentTable | null) => {
    const tournamentId = state.activeTournament?.id;
    const prevTableNumber = state.myTable?.tableNumber;
    const nextTableNumber = table?.tableNumber;
    if (tournamentId && nextTableNumber && prevTableNumber !== nextTableNumber) {
      this.subscribeTournamentWebSocket(tournamentId, nextTableNumber);
    }
    return {
      ...state,
      myTable: table
    };
  });

  readonly setMyPlayer = this.updater((state, player: TournamentPlayer | null) => ({
    ...state,
    myPlayer: player
  }));

  readonly setConnectionStatus = this.updater((state, status: 'connected' | 'disconnected' | 'reconnecting') => ({
    ...state,
    connectionStatus: status
  }));

  readonly setLastUpdate = this.updater((state, update: TournamentUpdate) => ({
    ...state,
    lastUpdate: update
  }));

  readonly setTableHandGame = this.updater((state, game: Game | null) => ({
    ...state,
    tableHandGame: game
  }));

  readonly updateTournamentState = this.updater((state, partialTournament: Partial<Tournament>) => ({
    ...state,
    activeTournament: state.activeTournament
      ? { ...state.activeTournament, ...partialTournament }
      : null
  }));

  readonly reset = this.updater(() => {
    this.wsService.unsubscribeFromTournament();
    if (this.refreshDebounceTimer) {
      clearTimeout(this.refreshDebounceTimer);
      this.refreshDebounceTimer = null;
    }
    return initialState;
  });






  readonly loadTournaments = this.effect<void>(trigger$ =>
    trigger$.pipe(
      tap(() => this.setLoading(true)),
      switchMap(() =>
        this.http.get<TournamentSummaryApi[]>(this.apiUrl).pipe(
          map(items => mapTournamentListFromApi(items)),
          tapResponse(
            tournaments => this.setTournaments(tournaments),
            (error: HttpErrorResponse) => this.handleError(error)
          )
        )
      )
    )
  );


  readonly loadTournament = this.effect<string>(tournamentId$ =>
    tournamentId$.pipe(
      tap(() => this.setLoading(true)),
      switchMap(tournamentId =>
        this.http.get<TournamentDetailApi>(`${this.apiUrl}/${tournamentId}`).pipe(
          map(api => mapTournamentDetailFromApi(api)),
          tapResponse(
            tournament => {
              this.setActiveTournament(tournament);

              const myPlayer = tournament.registeredPlayers.find(p => !p.isBot);
              if (myPlayer) {
                this.setMyPlayer(myPlayer);
                const myTable = tournament.tables.find(t =>
                  t.players.some(p => p.id === myPlayer.id)
                );
                this.setMyTable(myTable ?? null);
              }
            },
            (error: HttpErrorResponse) => this.handleError(error)
          )
        )
      )
    )
  );


  readonly registerForTournament = this.effect<{ tournamentId: string; playerName: string }>(
    params$ => params$.pipe(
      tap(() => this.setRegistering(true)),
      switchMap(({ tournamentId, playerName }) => {
        const user = this.authService.getCurrentUserValue();
        if (!user?.id) {
          this.setError('User profile not loaded. Please sign in again.');
          this.setRegistering(false);
          return EMPTY;
        }
        return this.http.post<TournamentDetailApi>(
          `${this.apiUrl}/${tournamentId}/register`,
          { playerId: user.id, playerName }
        ).pipe(
          map(api => mapTournamentDetailFromApi(api)),
          tapResponse(
            tournament => {
              this.setActiveTournament(tournament);
              const myPlayer = tournament.registeredPlayers.find(p => p.id === user.id);
              if (myPlayer) {
                this.setMyPlayer(myPlayer);
              }
              const myTable = tournament.tables.find(t =>
                t.players.some(p => p.id === user.id)
              );
              this.setMyTable(myTable ?? null);
              this.setRegistering(false);
            },
            (error: HttpErrorResponse) => {
              this.handleError(error);
              this.setRegistering(false);
            }
          )
        );
      })
    )
  );


  readonly unregisterFromTournament = this.effect<string>(tournamentId$ =>
    tournamentId$.pipe(
      tap(() => this.setLoading(true)),
      withLatestFrom(this.myPlayer$),
      switchMap(([tournamentId, myPlayer]) => {
        if (!myPlayer) {
          this.setLoading(false);
          return EMPTY;
        }

        return this.http.post(
          `${this.apiUrl}/${tournamentId}/unregister`,
          { playerId: myPlayer.id },
          { responseType: 'text' }
        ).pipe(
          tapResponse(
            () => {
              this.setMyPlayer(null);
              this.setMyTable(null);
              this.loadTournament(tournamentId);
            },
            (error: HttpErrorResponse) => this.handleError(error)
          )
        );
      })
    )
  );


  /**
   * Request a rebuy for the signed-in player in a rebuy tournament. The backend validates eligibility
   * (within the rebuy deadline level and under the max-rebuys cap) and rejects with 400 otherwise. On success
   * the tournament detail is reloaded so the player's refreshed chip stack and rebuy count are reflected.
   */
  readonly requestRebuy = this.effect<string>(tournamentId$ =>
    tournamentId$.pipe(
      tap(() => this.setLoading(true)),
      withLatestFrom(this.myPlayer$),
      switchMap(([tournamentId, myPlayer]) => {
        if (!myPlayer) {
          this.setLoading(false);
          return EMPTY;
        }

        return this.http.post(
          `${this.apiUrl}/${tournamentId}/rebuy`,
          { playerId: myPlayer.id }
        ).pipe(
          tapResponse(
            () => this.loadTournament(tournamentId),
            (error: HttpErrorResponse) => this.handleError(error)
          )
        );
      })
    )
  );


  readonly ensureTableHand = this.effect<{ tournamentId: string; tableId: string }>(params$ =>
    params$.pipe(
      switchMap(({ tournamentId, tableId }) =>
        this.http.post<Game>(`${this.apiUrl}/${tournamentId}/tables/${tableId}/hand`, {}).pipe(
          tapResponse(
            game => this.setTableHandGame(game),
            (error: HttpErrorResponse) => this.handleError(error)
          )
        )
      )
    )
  );

  readonly subscribeTournamentUpdates = this.effect<string>(tournamentId$ =>
    tournamentId$.pipe(
      tap(tournamentId => {
        this.setConnectionStatus('reconnecting');
        this.subscribeTournamentWebSocket(tournamentId, this.get().myTable?.tableNumber);
        this.startPollingFallback(tournamentId);
      })
    )
  );

  private initTournamentWebSocketListener(): void {
    if (this.tournamentWsListenerReady || !environment.enableWebSocket) {
      return;
    }
    this.tournamentWsListenerReady = true;

    this.wsService.tournamentUpdates$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(message => {
      const activeId = this.getCurrentTournamentId();
      if (!activeId || message.tournamentId !== activeId) {
        return;
      }
      this.handleTournamentWebSocketMessage(message);
    });

    this.wsService.connectionStatus$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(connected => {
      const tournamentId = this.getCurrentTournamentId();
      if (!tournamentId) {
        return;
      }
      if (connected) {
        this.setConnectionStatus('connected');
        this.subscribeTournamentWebSocket(tournamentId, this.get().myTable?.tableNumber);
      } else {
        this.setConnectionStatus('disconnected');
      }
    });
  }

  private subscribeTournamentWebSocket(tournamentId: string, tableNumber?: number): void {
    if (!environment.enableWebSocket) {
      return;
    }
    if (!this.wsService.isConnected()) {
      this.wsService.connect();
      return;
    }
    if (tableNumber != null && tableNumber > 0) {
      if (this.wsService.currentTournamentId() === tournamentId) {
        this.wsService.updateTournamentTableSubscription(tournamentId, tableNumber);
      } else {
        this.wsService.subscribeToTournament(tournamentId, tableNumber);
      }
    } else {
      this.wsService.subscribeToTournament(tournamentId);
    }
  }

  private handleTournamentWebSocketMessage(message: TournamentMessage): void {
    this.setConnectionStatus('connected');
    const tournamentId = message.tournamentId;

    switch (message.type) {
      case 'BLIND_LEVEL_INCREASED': {
        const active = this.get().activeTournament;
        if (active) {
          const patch = blindLevelUpdateFromWs(message.data, active);
          if (patch) {
            this.updateTournamentState(patch);
            return;
          }
        }
        this.scheduleTournamentRefresh(tournamentId);
        break;
      }
      case 'PLAYER_ELIMINATED': {
        const active = this.get().activeTournament;
        if (active) {
          const patch = playerEliminatedFromWs(message.data, active);
          if (patch) {
            this.updateTournamentState(patch);
            this.syncMyPlayerAndTable();
            const playerName = String(message.data['playerName'] ?? 'Player');
            const pos = Number(message.data['finishPosition']);
            this.setLastUpdate({
              type: 'PLAYER_ELIMINATED',
              tournamentId,
              data: patch,
              message: Number.isFinite(pos)
                ? `${playerName} eliminated (#${pos})`
                : `${playerName} eliminated`,
              timestamp: Date.now()
            });
            return;
          }
        }
        this.scheduleTournamentRefresh(tournamentId);
        break;
      }
      case 'TABLE_REBALANCED': {
        const active = this.get().activeTournament;
        if (active) {
          const result = tableRebalancedFromWs(
            message.data,
            active,
            this.get().myPlayer?.id
          );
          if (result.patch) {
            this.updateTournamentState(result.patch);
            if (result.myNewTableNumber) {
              this.subscribeTournamentWebSocket(tournamentId, result.myNewTableNumber);
            }
            this.syncMyPlayerAndTable();
            const moveCount = (message.data['playerMoves'] as unknown[] | undefined)?.length ?? 0;
            this.setLastUpdate({
              type: 'TABLE_BREAK',
              tournamentId,
              data: result.patch,
              message: result.patch.status === 'FINAL_TABLE'
                ? 'Final table formed — players consolidated'
                : moveCount > 0
                  ? `Tables rebalanced (${moveCount} move${moveCount === 1 ? '' : 's'})`
                  : 'Tables rebalanced',
              timestamp: Date.now()
            });
            return;
          }
        }
        this.scheduleTournamentRefresh(tournamentId);
        break;
      }
      case 'FINAL_TABLE_REACHED': {
        const active = this.get().activeTournament;
        if (active && !message.data['playerMoves']) {
          const patch = finalTableReachedFromWs(message.data, active);
          this.updateTournamentState(patch);
          this.setLastUpdate({
            type: 'FINAL_TABLE',
            tournamentId,
            data: patch,
            message: 'Final table reached!',
            timestamp: Date.now()
          });
          return;
        }
        this.scheduleTournamentRefresh(tournamentId);
        break;
      }
      case 'TOURNAMENT_STARTED': {
        const active = this.get().activeTournament;
        if (active && (active.status === 'REGISTERING' || active.status === 'STARTING')) {
          const patch = { status: 'RUNNING' as const };
          this.updateTournamentState(patch);
          this.setLastUpdate({
            type: 'TOURNAMENT_STARTED',
            tournamentId,
            data: patch,
            message: 'Tournament started',
            timestamp: Date.now()
          });
          return;
        }
        this.scheduleTournamentRefresh(tournamentId);
        break;
      }
      case 'TOURNAMENT_COMPLETED': {
        this.updateTournamentState({ status: 'FINISHED' });
        this.setLastUpdate({
          type: 'TOURNAMENT_END',
          tournamentId,
          data: { status: 'FINISHED' },
          message: 'Tournament completed',
          timestamp: Date.now()
        });
        this.scheduleTournamentRefresh(tournamentId, 500);
        break;
      }
      case 'TABLE_CREATED':
      case 'PLAYER_REGISTERED':
        this.scheduleTournamentRefresh(tournamentId);
        break;
      default:
        this.scheduleTournamentRefresh(tournamentId, 800);
    }
  }

  private syncMyPlayerAndTable(): void {
    const tournament = this.get().activeTournament;
    const myPlayer = this.get().myPlayer;
    if (!tournament || !myPlayer) {
      return;
    }
    const updatedPlayer = tournament.registeredPlayers.find(p => p.id === myPlayer.id);
    if (updatedPlayer) {
      this.setMyPlayer(updatedPlayer);
    }
    const myTable = tournament.tables.find(t =>
      t.players.some(p => p.id === myPlayer.id)
    );
    this.setMyTable(myTable ?? null);
  }

  private scheduleTournamentRefresh(tournamentId: string, delayMs = 250): void {
    if (this.refreshDebounceTimer) {
      clearTimeout(this.refreshDebounceTimer);
    }
    this.refreshDebounceTimer = setTimeout(() => {
      this.loadTournament(tournamentId);
      this.refreshDebounceTimer = null;
    }, delayMs);
  }

  private startPollingFallback(tournamentId: string): void {
    const fetchTournament = () =>
      this.http.get<TournamentDetailApi>(`${this.apiUrl}/${tournamentId}`).pipe(
        map(api => mapTournamentDetailFromApi(api)),
        catchError(() => EMPTY)
      );

    timer(0, 5000).pipe(
      takeUntil(this.destroy$),
      filter(() => this.get().connectionStatus !== 'connected'),
      switchMap(() => fetchTournament())
    ).subscribe(tournament => this.applyTournamentSnapshot(tournament));

    timer(0, 30000).pipe(
      takeUntil(this.destroy$),
      filter(() => this.get().connectionStatus === 'connected'),
      switchMap(() => fetchTournament())
    ).subscribe(tournament => this.applyTournamentSnapshot(tournament));
  }

  private applyTournamentSnapshot(tournament: Tournament): void {
    this.setActiveTournament(tournament);
    this.syncMyPlayerAndTable();
  }





  private handleError(error: HttpErrorResponse): void {
    const errorMessage = error.error instanceof ErrorEvent
      ? `Error: ${error.error.message}`
      : error.error?.message || `Error Code: ${error.status}, Message: ${error.message}`;

    this.setError(errorMessage);
    this.logDebug('API Error', { error, errorMessage });
  }

  private logDebug(message: string, data?: unknown): void {
    if (environment.logApiCalls) {
      console.log(`[TournamentStore] ${message}`, data);
    }
  }


  getMyPlayer(): TournamentPlayer | null {
    return this.get().myPlayer;
  }


  getCurrentTournamentId(): string | null {
    return this.get().activeTournament?.id ?? null;
  }


  isPlayerRegistered(): boolean {
    return this.get().myPlayer !== null;
  }
}
