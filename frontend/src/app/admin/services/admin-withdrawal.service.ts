import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AdminWithdrawal, WithdrawalSigningRequest } from '../models/withdrawal.models';

/** Admin withdrawal moderation: list pending, approve/reject, and the offline-signer (PSBT) handoff. */
@Injectable({ providedIn: 'root' })
export class AdminWithdrawalService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/admin/wallet/withdrawals`;

  listPending(): Observable<AdminWithdrawal[]> {
    return this.http.get<AdminWithdrawal[]>(this.url);
  }

  approve(id: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/approve`, {});
  }

  reject(id: string, reason: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/reject`, { reason });
  }

  /** Export the signer-ready intent for an APPROVED withdrawal (offline-pool handoff). */
  exportUnsigned(id: string): Observable<WithdrawalSigningRequest> {
    return this.http.get<WithdrawalSigningRequest>(`${this.url}/${id}/unsigned`);
  }

  /** Record the tx id after the offline signer broadcast the transaction. */
  recordBroadcast(id: string, txId: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/broadcast`, { txId });
  }
}
