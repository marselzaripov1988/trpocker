import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface WalletBalance {
  asset: string;
  network: string;
  balance: string;
}

export interface DepositAddress {
  asset: string;
  network: string;
  address: string;
}

export type WithdrawalStatus =
  | 'PENDING_APPROVAL' | 'APPROVED' | 'BROADCAST' | 'CONFIRMED' | 'FAILED' | 'REJECTED';

export interface Withdrawal {
  id: string;
  asset: string;
  network: string;
  toAddress: string;
  amount: string;
  status: WithdrawalStatus;
  txId: string | null;
}

export interface CreateWithdrawal {
  /** CryptoAsset enum name, e.g. ETH / BTC / USDT_ERC20 / USDT_TRC20 / LTC. */
  asset: string;
  toAddress: string;
  amount: string;
}

/** Player-facing crypto wallet API: balances, deposit address, withdrawals. */
@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/wallet`;

  balances(): Observable<WalletBalance[]> {
    return this.http.get<WalletBalance[]>(`${this.url}/balances`);
  }

  depositAddress(asset: string): Observable<DepositAddress> {
    const params = new HttpParams().set('asset', asset);
    return this.http.post<DepositAddress>(`${this.url}/deposit-address`, null, { params });
  }

  withdrawals(): Observable<Withdrawal[]> {
    return this.http.get<Withdrawal[]>(`${this.url}/withdrawals`);
  }

  requestWithdrawal(body: CreateWithdrawal): Observable<Withdrawal> {
    return this.http.post<Withdrawal>(`${this.url}/withdrawals`, body);
  }
}
