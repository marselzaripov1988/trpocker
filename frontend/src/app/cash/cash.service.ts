import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  CashActionResult,
  CashLeaveResult,
  CashTable,
  CashTableState,
  PlayerAction,
  SitDownResult,
} from './cash.models';

/** Player-facing cash (ring) table REST client (/v1/cash/tables). */
@Injectable({ providedIn: 'root' })
export class CashService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/cash/tables`;

  /** Open tables in the lobby. */
  list(): Observable<CashTable[]> {
    return this.http.get<CashTable[]>(this.url);
  }

  /** Full table state (seats + the current hand, with only your own hole cards). */
  state(id: string): Observable<CashTableState> {
    return this.http.get<CashTableState>(`${this.url}/${id}`);
  }

  /** Sit down with a real-money buy-in. */
  sit(id: string, buyIn: number): Observable<SitDownResult> {
    return this.http.post<SitDownResult>(`${this.url}/${id}/sit`, { buyIn });
  }

  /** Stand up (cash out now, or deferred to the end of the current hand). */
  leave(id: string): Observable<CashLeaveResult> {
    return this.http.post<CashLeaveResult>(`${this.url}/${id}/leave`, {});
  }

  /** Deal the next hand (needs 2+ seated players). */
  deal(id: string): Observable<CashTableState> {
    return this.http.post<CashTableState>(`${this.url}/${id}/deal`, {});
  }

  /** Take an action on the current hand; amount (money) is only needed for BET / RAISE. */
  act(id: string, action: PlayerAction, amount?: number): Observable<CashActionResult> {
    return this.http.post<CashActionResult>(`${this.url}/${id}/act`, { action, amount: amount ?? null });
  }
}
