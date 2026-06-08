import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CashTable } from '../../cash/cash.models';

/** Mirrors the backend CreateCashTableRequest (BigDecimal money fields are sent as plain numbers). */
export interface CreateCashTableRequest {
  name: string;
  asset: string;
  smallBlind: number;
  bigBlind: number;
  minBuyIn: number;
  maxBuyIn: number;
  maxSeats: number;
  rakeBasisPoints: number;
  rakeCap: number;
}

/**
 * Admin control plane for real-money cash (ring) tables — see AdminCashTableController. Creating a table is
 * admin-only (`POST /v1/admin/cash/tables`); the listing reuses the player-facing `GET /v1/cash/tables`
 * (active tables) since the admin controller exposes only create.
 */
@Injectable({ providedIn: 'root' })
export class AdminCashService {
  private readonly http = inject(HttpClient);
  private readonly createUrl = `${environment.apiUrl}/v1/admin/cash/tables`;
  private readonly listUrl = `${environment.apiUrl}/v1/cash/tables`;

  create(request: CreateCashTableRequest): Observable<CashTable> {
    return this.http.post<CashTable>(this.createUrl, request);
  }

  list(): Observable<CashTable[]> {
    return this.http.get<CashTable[]>(this.listUrl);
  }
}
