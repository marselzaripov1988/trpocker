import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** One buyable higher-level pyramid seat ("ticket") offered to a player. */
export interface PyramidTicket {
  level: number;
  seatIndex: number;
  price: number;
  /** CryptoAsset enum name, e.g. USDT_TRC20. */
  asset: string;
}

/** Confirmation of a bought higher-level pyramid seat. */
export interface PyramidSeatPurchase {
  tournamentId: string;
  playerId: string;
  level: number;
  seatIndex: number;
  price: number;
  asset: string;
}

/**
 * Player-facing buy-up pyramid API: list the buyable higher-level seats and buy one before the tournament
 * starts. Buying charges the caller's crypto wallet at the seat price (which replaces the flat buy-in).
 */
@Injectable({ providedIn: 'root' })
export class TournamentPyramidService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/tournaments`;

  tickets(tournamentId: string): Observable<PyramidTicket[]> {
    return this.http.get<PyramidTicket[]>(`${this.url}/${tournamentId}/pyramid/tickets`);
  }

  buySeat(tournamentId: string, level: number, seatIndex: number): Observable<PyramidSeatPurchase> {
    return this.http.post<PyramidSeatPurchase>(
      `${this.url}/${tournamentId}/pyramid/buy-seat`, { level, seatIndex });
  }
}
