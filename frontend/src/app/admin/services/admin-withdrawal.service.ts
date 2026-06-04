import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminWithdrawal, WithdrawalSigningRequest,
  EthUnsignedTx, BtcUnsignedTx, EthConfirmation, BtcConfirmation
} from '../models/withdrawal.models';

/** Admin withdrawal moderation: list pending, approve/reject, and the offline-signer (PSBT) handoff. */
@Injectable({ providedIn: 'root' })
export class AdminWithdrawalService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/admin/wallet/withdrawals`;

  /** List withdrawals for review. No status → the open set (PENDING_APPROVAL + APPROVED); else that status. */
  listPending(status?: string): Observable<AdminWithdrawal[]> {
    const options = status ? { params: new HttpParams().set('status', status) } : {};
    return this.http.get<AdminWithdrawal[]>(this.url, options);
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

  // ---- ETH/ERC-20 coordinator (assemble from the node → broadcast signed → reconcile) -------------
  ethUnsigned(id: string): Observable<EthUnsignedTx> {
    return this.http.get<EthUnsignedTx>(`${this.url}/${id}/eth-unsigned`);
  }
  ethBroadcast(id: string, signedRawTx: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/eth-broadcast`, { signedRawTx });
  }
  ethReconcile(id: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/eth-reconcile`, {});
  }
  ethConfirmation(id: string): Observable<EthConfirmation> {
    return this.http.get<EthConfirmation>(`${this.url}/${id}/eth-confirmation`);
  }

  // ---- BTC (P2WPKH) coordinator --------------------------------------------------------------------
  btcUnsigned(id: string): Observable<BtcUnsignedTx> {
    return this.http.get<BtcUnsignedTx>(`${this.url}/${id}/btc-unsigned`);
  }
  btcBroadcast(id: string, signedRawTx: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/btc-broadcast`, { signedRawTx });
  }
  btcReconcile(id: string): Observable<AdminWithdrawal> {
    return this.http.post<AdminWithdrawal>(`${this.url}/${id}/btc-reconcile`, {});
  }
  btcConfirmation(id: string): Observable<BtcConfirmation> {
    return this.http.get<BtcConfirmation>(`${this.url}/${id}/btc-confirmation`);
  }
}
