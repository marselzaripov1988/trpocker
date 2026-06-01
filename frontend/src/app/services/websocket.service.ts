import { Injectable, inject, signal, computed } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';
import SockJS from 'sockjs-client';
import { Stomp } from '@stomp/stompjs';
import { AuthService } from './auth.service';
import { Game } from '../model/game';
import { environment } from '../../environments/environment';





export enum ConnectionState {
  DISCONNECTED = 'DISCONNECTED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  RECONNECTING = 'RECONNECTING',
  ERROR = 'ERROR'
}

export interface GameUpdateMessage {
  type: string;
  gameState: Game | null;
  action?: PlayerActionMessage | null;
  message: string;
  timestamp?: number;
  sequenceNumber?: number;
}

export interface PlayerActionMessage {
  playerId: string;
  playerName?: string;
  action: string;
  amount: number;
  remainingChips?: number;
  currentBet?: number;
}

export interface ReconnectRequest {
  gameId: string;
  lastEventSequence: number;
  lastKnownPhase: string | null;
  disconnectedAt: string;
}

export interface GameStateSnapshot {
  success: boolean;
  error?: string;
  game: Game | null;
  currentPlayerId?: string;
  missedEvents: GameUpdateMessage[];
  lastEventSequence: number;
  serverTime: string;
  sessionId?: string;
}

export interface TournamentMessage {
  type: string;
  tournamentId: string;
  data: Record<string, unknown>;
  timestamp?: string;
}




const RECONNECT_CONFIG = {
  INITIAL_DELAY_MS: 1000,      
  MAX_DELAY_MS: 30000,         
  MULTIPLIER: 2,               
  MAX_ATTEMPTS: 10,            
  JITTER_FACTOR: 0.2           
};




@Injectable({
  providedIn: 'root'
})
export class WebSocketService {
  private readonly authService = inject(AuthService);
  
  
  private readonly WEBSOCKET_URL = environment.wsUrl || 'http://localhost:8080/ws';
  
  
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stompClient: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private gameSubscription: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private userQueueSubscription: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stateRecoverySubscription: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private tournamentSubscription: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private tournamentTableSubscription: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private tournamentShardSubscription: any = null;

  
  
  
  
  
  readonly connectionState = signal<ConnectionState>(ConnectionState.DISCONNECTED);
  readonly currentGameId = signal<string | null>(null);
  readonly currentTournamentId = signal<string | null>(null);
  readonly currentTournamentTableNumber = signal<number | null>(null);
  readonly lastEventSequence = signal<number>(0);
  readonly reconnectAttemptsRemaining = signal<number>(RECONNECT_CONFIG.MAX_ATTEMPTS);
  
  
  readonly isConnected = computed(() => this.connectionState() === ConnectionState.CONNECTED);
  readonly isReconnecting = computed(() => this.connectionState() === ConnectionState.RECONNECTING);

  
  
  
  
  
  private connectionStatusSubject = new BehaviorSubject<boolean>(false);
  public connectionStatus$ = this.connectionStatusSubject.asObservable();

  
  private gameUpdatesSubject = new Subject<GameUpdateMessage>();
  public gameUpdates$ = this.gameUpdatesSubject.asObservable();

  private tournamentUpdatesSubject = new Subject<TournamentMessage>();
  public tournamentUpdates$ = this.tournamentUpdatesSubject.asObservable();

  
  private stateSnapshotSubject = new Subject<GameStateSnapshot>();
  public stateSnapshot$ = this.stateSnapshotSubject.asObservable();

  
  private errorSubject = new Subject<string>();
  public errors$ = this.errorSubject.asObservable();

  
  
  
  
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private disconnectedAt: Date | null = null;
  private lastKnownPhase: string | null = null;

  constructor() {
    
    this.authService.isAuthenticated$.subscribe(isAuth => {
      if (isAuth && this.connectionState() === ConnectionState.DISCONNECTED) {
        this.connect();
      } else if (!isAuth && this.connectionState() !== ConnectionState.DISCONNECTED) {
        this.disconnect();
      }
    });
  }

  
  
  

  
  connect(): void {
    if (this.connectionState() === ConnectionState.CONNECTED ||
        this.connectionState() === ConnectionState.CONNECTING) {
      return;
    }

    const token = this.authService.getToken();
    if (!token) {
      console.warn('[WebSocket] Cannot connect: No authentication token');
      this.connectionState.set(ConnectionState.ERROR);
      return;
    }

    this.connectionState.set(ConnectionState.CONNECTING);
    console.log(`[WebSocket] Connecting to ${this.WEBSOCKET_URL}`);

    try {
      const socket = new SockJS(this.WEBSOCKET_URL);
      this.stompClient = Stomp.over(socket);
      
      
      if (environment.production) {
        this.stompClient.debug = null;
      }

      const headers = {
        'Authorization': `Bearer ${token}`
      };

      this.stompClient.connect(
        headers,
        (frame: unknown) => this.onConnected(frame),
        (error: unknown) => this.onConnectionError(error)
      );

      
      if (this.stompClient.ws) {
        this.stompClient.ws.onclose = () => this.onDisconnected();
      }

    } catch (error: unknown) {
      console.error('[WebSocket] Failed to create connection:', error);
      this.handleConnectionError(error);
    }
  }

  
  disconnect(): void {
    this.cancelReconnectTimer();
    
    if (this.stompClient) {
      try {
        
        this.cleanupSubscriptions();
        
        if (this.stompClient.connected) {
          this.stompClient.disconnect(() => {
            console.log('[WebSocket] Disconnected gracefully');
          });
        }
      } catch (e) {
        console.warn('[WebSocket] Error during disconnect:', e);
      }
      
      this.stompClient = null;
    }

    this.connectionState.set(ConnectionState.DISCONNECTED);
    this.connectionStatusSubject.next(false);
    this.currentGameId.set(null);
    this.resetReconnectState();
  }

  
  forceReconnect(): void {
    console.log('[WebSocket] Force reconnect requested');
    this.resetReconnectState();
    this.disconnect();
    setTimeout(() => this.connect(), 100);
  }

  
  
  

  private onConnected(frame: unknown): void {
    console.log('[WebSocket] Connected successfully:', frame);
    
    this.connectionState.set(ConnectionState.CONNECTED);
    this.connectionStatusSubject.next(true);
    this.resetReconnectState();

    
    const gameId = this.currentGameId();
    if (gameId) {
      this.subscribeToGame(gameId);
      if (this.disconnectedAt) {
        this.requestStateRecovery(gameId);
      }
    }

    const tournamentId = this.currentTournamentId();
    if (tournamentId) {
      this.subscribeToTournament(tournamentId, this.currentTournamentTableNumber());
    }
  }

  private onDisconnected(): void {
    console.log('[WebSocket] Connection lost');
    
    this.disconnectedAt = new Date();
    this.connectionStatusSubject.next(false);
    
    
    if (this.authService.isAuthenticated()) {
      this.connectionState.set(ConnectionState.RECONNECTING);
      this.scheduleReconnect();
    } else {
      this.connectionState.set(ConnectionState.DISCONNECTED);
    }
  }

  private onConnectionError(error: unknown): void {
    console.error('[WebSocket] Connection error:', error);
    this.handleConnectionError(error);
  }

  
  
  

  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= RECONNECT_CONFIG.MAX_ATTEMPTS) {
      console.error('[WebSocket] Max reconnection attempts reached');
      this.connectionState.set(ConnectionState.ERROR);
      this.errorSubject.next('Connection lost. Maximum reconnection attempts reached.');
      return;
    }

    this.reconnectAttempts++;
    this.reconnectAttemptsRemaining.set(RECONNECT_CONFIG.MAX_ATTEMPTS - this.reconnectAttempts);

    const delay = this.calculateBackoffDelay();
    console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts}/${RECONNECT_CONFIG.MAX_ATTEMPTS})`);

    this.cancelReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      if (this.connectionState() === ConnectionState.RECONNECTING) {
        this.connect();
      }
    }, delay);
  }

  
  private calculateBackoffDelay(): number {
    const exponentialDelay = RECONNECT_CONFIG.INITIAL_DELAY_MS * 
      Math.pow(RECONNECT_CONFIG.MULTIPLIER, this.reconnectAttempts - 1);
    
    const cappedDelay = Math.min(exponentialDelay, RECONNECT_CONFIG.MAX_DELAY_MS);
    
    
    const jitter = cappedDelay * RECONNECT_CONFIG.JITTER_FACTOR * (Math.random() * 2 - 1);
    
    return Math.round(cappedDelay + jitter);
  }

  private cancelReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private resetReconnectState(): void {
    this.reconnectAttempts = 0;
    this.reconnectAttemptsRemaining.set(RECONNECT_CONFIG.MAX_ATTEMPTS);
    this.cancelReconnectTimer();
  }

  
  
  

  
  subscribeToGame(gameId: string): void {
    if (this.connectionState() !== ConnectionState.CONNECTED || !this.stompClient) {
      console.warn('[WebSocket] Cannot subscribe: Not connected');
      return;
    }

    
    const currentGame = this.currentGameId();
    if (currentGame && currentGame !== gameId) {
      this.unsubscribeFromGame();
    }

    this.currentGameId.set(gameId);
    console.log(`[WebSocket] Subscribing to game: ${gameId}`);

    
    this.gameSubscription = this.stompClient.subscribe(
      `/topic/game/${gameId}`,
      (message: { body: string }) => this.handleGameUpdate(message)
    );

    
    this.userQueueSubscription = this.stompClient.subscribe(
      '/user/queue/messages',
      (message: { body: string }) => this.handleUserMessage(message)
    );

    
    this.stateRecoverySubscription = this.stompClient.subscribe(
      '/user/queue/state',
      (message: { body: string }) => this.handleStateRecovery(message)
    );
  }

  
  unsubscribeFromGame(): void {
    if (this.gameSubscription) {
      try { this.gameSubscription.unsubscribe(); } catch { /* ignore */ }
      this.gameSubscription = null;
    }
    if (this.userQueueSubscription) {
      try { this.userQueueSubscription.unsubscribe(); } catch { /* ignore */ }
      this.userQueueSubscription = null;
    }
    if (this.stateRecoverySubscription) {
      try { this.stateRecoverySubscription.unsubscribe(); } catch { /* ignore */ }
      this.stateRecoverySubscription = null;
    }
    this.currentGameId.set(null);
    this.lastEventSequence.set(0);
    this.lastKnownPhase = null;
    console.log('[WebSocket] Unsubscribed from game');
  }

  subscribeToTournament(tournamentId: string, tableNumber?: number | null): void {
    if (this.connectionState() !== ConnectionState.CONNECTED || !this.stompClient) {
      console.warn('[WebSocket] Cannot subscribe to tournament: Not connected');
      return;
    }

    const previousTournament = this.currentTournamentId();
    if (previousTournament && previousTournament !== tournamentId) {
      this.unsubscribeFromTournament();
    }

    if (previousTournament === tournamentId) {
      this.cleanupTournamentTableSubscriptions();
      if (this.tournamentSubscription) {
        try { this.tournamentSubscription.unsubscribe(); } catch { /* ignore */ }
        this.tournamentSubscription = null;
      }
    }

    this.currentTournamentId.set(tournamentId);
    this.currentTournamentTableNumber.set(tableNumber ?? null);

    console.log(`[WebSocket] Subscribing to tournament ${tournamentId}`, tableNumber != null ? `table ${tableNumber}` : '');

    this.tournamentSubscription = this.stompClient.subscribe(
      `/topic/tournament/${tournamentId}`,
      (message: { body: string }) => this.handleTournamentMessage(message)
    );

    if (tableNumber != null && tableNumber > 0) {
      this.subscribeToTournamentTableTopics(tournamentId, tableNumber);
    }
  }

  updateTournamentTableSubscription(tournamentId: string, tableNumber: number): void {
    if (this.currentTournamentId() !== tournamentId) {
      this.subscribeToTournament(tournamentId, tableNumber);
      return;
    }
    if (this.currentTournamentTableNumber() === tableNumber) {
      return;
    }
    this.cleanupTournamentTableSubscriptions();
    this.currentTournamentTableNumber.set(tableNumber);
    if (this.connectionState() === ConnectionState.CONNECTED && this.stompClient) {
      this.subscribeToTournamentTableTopics(tournamentId, tableNumber);
    }
  }

  unsubscribeFromTournament(): void {
    this.cleanupTournamentSubscriptions();
    this.currentTournamentId.set(null);
    this.currentTournamentTableNumber.set(null);
    console.log('[WebSocket] Unsubscribed from tournament');
  }

  private subscribeToTournamentTableTopics(tournamentId: string, tableNumber: number): void {
    const tableDestination = `/topic/tournament/${tournamentId}/table/${tableNumber}`;
    const shard = this.resolveShard(tableNumber);
    const shardDestination = `/topic/tournament/${tournamentId}/shard/${shard}`;

    this.tournamentTableSubscription = this.stompClient.subscribe(
      tableDestination,
      (message: { body: string }) => this.handleTournamentMessage(message)
    );
    this.tournamentShardSubscription = this.stompClient.subscribe(
      shardDestination,
      (message: { body: string }) => this.handleTournamentMessage(message)
    );
  }

  private resolveShard(tableNumber: number): number {
    const shardCount = environment.tournamentShardCount ?? 16;
    return ((tableNumber - 1) % shardCount + shardCount) % shardCount;
  }

  private handleTournamentMessage(message: { body: string }): void {
    try {
      const parsed = JSON.parse(message.body) as TournamentMessage;
      const tournamentId = parsed.tournamentId ?? (parsed as unknown as { tournamentId: string }).tournamentId;
      if (!tournamentId) {
        return;
      }
      this.tournamentUpdatesSubject.next({
        type: parsed.type,
        tournamentId: String(tournamentId),
        data: parsed.data ?? {},
        timestamp: parsed.timestamp
      });
    } catch (error: unknown) {
      console.error('[WebSocket] Error parsing tournament message:', error);
      this.errorSubject.next('Failed to parse tournament update');
    }
  }

  private cleanupTournamentSubscriptions(): void {
    if (this.tournamentSubscription) {
      try { this.tournamentSubscription.unsubscribe(); } catch { /* ignore */ }
      this.tournamentSubscription = null;
    }
    this.cleanupTournamentTableSubscriptions();
  }

  private cleanupTournamentTableSubscriptions(): void {
    if (this.tournamentTableSubscription) {
      try { this.tournamentTableSubscription.unsubscribe(); } catch { /* ignore */ }
      this.tournamentTableSubscription = null;
    }
    if (this.tournamentShardSubscription) {
      try { this.tournamentShardSubscription.unsubscribe(); } catch { /* ignore */ }
      this.tournamentShardSubscription = null;
    }
  }

  private cleanupSubscriptions(): void {
    this.unsubscribeFromGame();
    this.unsubscribeFromTournament();
  }

  
  
  

  private handleGameUpdate(message: { body: string }): void {
    try {
      const gameUpdate: GameUpdateMessage = JSON.parse(message.body);


      if (gameUpdate.sequenceNumber !== undefined) {
        const lastSequence = this.lastEventSequence();
        if (lastSequence > 0 && gameUpdate.sequenceNumber <= lastSequence) {
          console.warn('[WebSocket] Ignoring out-of-order event:', gameUpdate.sequenceNumber, '<=', lastSequence);
          return;
        }
        this.lastEventSequence.set(gameUpdate.sequenceNumber);
      }


      if (gameUpdate.gameState?.phase) {
        this.lastKnownPhase = gameUpdate.gameState.phase;
      }

      this.gameUpdatesSubject.next(gameUpdate);
    } catch (error: unknown) {
      console.error('[WebSocket] Error parsing game update:', error);
      this.errorSubject.next('Failed to parse game update');
    }
  }

  private handleUserMessage(message: { body: string }): void {
    try {
      const userMessage = JSON.parse(message.body);
      console.log('[WebSocket] Received personal message:', userMessage);
    } catch (error: unknown) {
      console.error('[WebSocket] Error parsing personal message:', error);
    }
  }

  private handleStateRecovery(message: { body: string }): void {
    try {
      const snapshot: GameStateSnapshot = JSON.parse(message.body);
      console.log('[WebSocket] State recovery received:', snapshot);
      
      if (snapshot.success) {
        
        this.lastEventSequence.set(snapshot.lastEventSequence);
        
        
        for (const event of snapshot.missedEvents) {
          this.gameUpdatesSubject.next(event);
        }
        
        
        this.stateSnapshotSubject.next(snapshot);
      } else {
        console.error('[WebSocket] State recovery failed:', snapshot.error);
        this.errorSubject.next(snapshot.error || 'State recovery failed');
      }
      
      
      this.disconnectedAt = null;
      
    } catch (error: unknown) {
      console.error('[WebSocket] Error parsing state recovery:', error);
      this.errorSubject.next('Failed to parse state recovery');
    }
  }

  
  
  

  
  private requestStateRecovery(gameId: string): void {
    if (!this.stompClient || this.connectionState() !== ConnectionState.CONNECTED) {
      console.warn('[WebSocket] Cannot request state recovery: Not connected');
      return;
    }

    const request: ReconnectRequest = {
      gameId: gameId,
      lastEventSequence: this.lastEventSequence(),
      lastKnownPhase: this.lastKnownPhase,
      disconnectedAt: this.disconnectedAt?.toISOString() || new Date().toISOString()
    };

    console.log('[WebSocket] Requesting state recovery:', request);

    try {
      this.stompClient.send(
        '/app/reconnect',
        {},
        JSON.stringify(request)
      );
    } catch (error: unknown) {
      console.error('[WebSocket] Failed to request state recovery:', error);
      this.errorSubject.next('Failed to request state recovery');
    }
  }

  
  
  

  
  sendPlayerAction(action: PlayerActionMessage): void {
    if (this.connectionState() !== ConnectionState.CONNECTED || !this.stompClient) {
      console.warn('[WebSocket] Cannot send action: Not connected');
      return;
    }

    const gameId = this.currentGameId();
    if (!gameId) {
      console.warn('[WebSocket] Cannot send action: No game selected');
      return;
    }

    try {
      this.stompClient.send(
        `/app/game/${gameId}/action`,
        {},
        JSON.stringify(action)
      );
      console.log('[WebSocket] Player action sent:', action);
    } catch (error: unknown) {
      console.error('[WebSocket] Failed to send player action:', error);
      this.errorSubject.next('Failed to send player action');
    }
  }

  
  joinGame(playerName: string): void {
    if (this.connectionState() !== ConnectionState.CONNECTED || !this.stompClient) {
      console.warn('[WebSocket] Cannot join game: Not connected');
      return;
    }

    const gameId = this.currentGameId();
    if (!gameId) {
      console.warn('[WebSocket] Cannot join game: No game selected');
      return;
    }

    try {
      this.stompClient.send(
        `/app/game/${gameId}/join`,
        {},
        JSON.stringify(playerName)
      );
      console.log('[WebSocket] Joined game:', gameId);
    } catch (error: unknown) {
      console.error('[WebSocket] Failed to join game:', error);
      this.errorSubject.next('Failed to join game');
    }
  }

  
  leaveGame(playerName: string): void {
    if (this.connectionState() !== ConnectionState.CONNECTED || !this.stompClient) {
      return;
    }

    const gameId = this.currentGameId();
    if (!gameId) {
      return;
    }

    try {
      this.stompClient.send(
        `/app/game/${gameId}/leave`,
        {},
        JSON.stringify(playerName)
      );
      console.log('[WebSocket] Left game:', gameId);
    } catch (error: unknown) {
      console.error('[WebSocket] Failed to leave game:', error);
    } finally {
      this.unsubscribeFromGame();
    }
  }

  
  
  

  private handleConnectionError(error: unknown): void {
    this.connectionStatusSubject.next(false);
    
    let errorMessage = 'WebSocket connection failed';
    if (error && typeof error === 'object' && error !== null && 'message' in error) {
      errorMessage += ': ' + (error as { message: string }).message;
    }
    
    this.errorSubject.next(errorMessage);

    
    if (this.authService.isAuthenticated()) {
      this.connectionState.set(ConnectionState.RECONNECTING);
      this.scheduleReconnect();
    } else {
      this.connectionState.set(ConnectionState.ERROR);
    }
  }

  
  
  

  
  getCurrentGameId(): string | null {
    return this.currentGameId();
  }

  
  getConnectionState(): ConnectionState {
    return this.connectionState();
  }
}
