import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateFederationRequest, FederationDetail, FederationRefund, FederationWalletStats, PrizeConfigRequest,
  SolAtaBatchUnsigned, SolRefundUnsigned, WalletImportEntry
} from '../models/admin-federation.models';

/** Admin control plane for federated (sharded) pyramids — see AdminPyramidFederationController. */
@Injectable({ providedIn: 'root' })
export class AdminFederationService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/admin/pyramid-federations`;

  create(request: CreateFederationRequest): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(this.url, request);
  }

  get(id: string): Observable<FederationDetail> {
    return this.http.get<FederationDetail>(`${this.url}/${id}`);
  }

  promote(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/promote`, {});
  }

  scheduleFinal(id: string, startAt: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/schedule-final`, { startAt });
  }

  startFinal(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/start-final`, {});
  }

  runFinal(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/run-final`, {});
  }

  drainShards(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/drain-shards`, {});
  }

  openShardForBuyUp(id: string, shardIndex: number): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/shards/${shardIndex}/open-buyup`, {});
  }

  closeBuyUp(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/close-buyup`, {});
  }

  distribute(id: string): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/distribute`, {});
  }

  updatePrizeConfig(id: string, config: PrizeConfigRequest): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/prize-config`, config);
  }

  // --- Isolated custody: dedicated per-player wallets ---

  /** Dedicated-wallet pool dashboard: per-status counts, ATAs pre-created, total on-chain buy-in collected. */
  walletStats(id: string): Observable<FederationWalletStats> {
    return this.http.get<FederationWalletStats>(`${this.url}/${id}/wallet-stats`);
  }

  /** Import a chunk of offline-generated dedicated wallets (idempotent); returns the inserted count. The chunk's
   *  own `federationId` (if present) is forwarded so the server can reject a chunk from another tournament. */
  importWallets(id: string, wallets: WalletImportEntry[], federationId?: string): Observable<{ imported: number }> {
    return this.http.post<{ imported: number }>(`${this.url}/${id}/import-wallets`, { federationId, wallets });
  }

  /** Poll dedicated wallets on-chain and seat players whose buy-in landed; returns the seated count. */
  reconcileDeposits(id: string): Observable<{ seated: number }> {
    return this.http.post<{ seated: number }>(`${this.url}/${id}/reconcile-deposits`, {});
  }

  /** Release assigned-but-unfunded wallets past the deposit window; returns the released count. */
  releaseNoShows(id: string): Observable<{ released: number }> {
    return this.http.post<{ released: number }>(`${this.url}/${id}/release-no-shows`, {});
  }

  // --- Isolated custody: ATA lifecycle (offline-signed batches) ---

  ataCreateUnsigned(id: string, limit?: number): Observable<SolAtaBatchUnsigned> {
    const q = limit != null ? `?limit=${limit}` : '';
    return this.http.post<SolAtaBatchUnsigned>(`${this.url}/${id}/ata/create/unsigned${q}`, {});
  }

  ataCloseUnsigned(id: string, walletIds: string[]): Observable<SolAtaBatchUnsigned> {
    return this.http.post<SolAtaBatchUnsigned>(`${this.url}/${id}/ata/close/unsigned`, { walletIds });
  }

  ataBroadcast(id: string, signedTx: string): Observable<{ signature: string }> {
    return this.http.post<{ signature: string }>(`${this.url}/${id}/ata/broadcast`, { signedTx });
  }

  ataConfirmCreated(id: string, signature: string, walletIds: string[]): Observable<{ provisioned: number }> {
    return this.http.post<{ provisioned: number }>(`${this.url}/${id}/ata/create/confirm`, { signature, walletIds });
  }

  ataConfirmClosed(id: string, signature: string, walletIds: string[]): Observable<{ closed: number }> {
    return this.http.post<{ closed: number }>(`${this.url}/${id}/ata/close/confirm`, { signature, walletIds });
  }

  // --- Isolated custody: admin-approved refunds ---

  requestRefund(id: string, playerId: string): Observable<FederationRefund> {
    return this.http.post<FederationRefund>(`${this.url}/${id}/players/${playerId}/refund`, {});
  }

  requestRefundsForCancelled(id: string): Observable<{ requested: number }> {
    return this.http.post<{ requested: number }>(`${this.url}/${id}/refunds/request`, {});
  }

  approveRefund(refundId: string, toAddress: string): Observable<FederationRefund> {
    return this.http.post<FederationRefund>(`${this.url}/refunds/${refundId}/approve`, { toAddress });
  }

  rejectRefund(refundId: string, reason: string): Observable<FederationRefund> {
    return this.http.post<FederationRefund>(`${this.url}/refunds/${refundId}/reject`, { reason });
  }

  refundUnsigned(refundId: string): Observable<SolRefundUnsigned> {
    return this.http.get<SolRefundUnsigned>(`${this.url}/refunds/${refundId}/unsigned`);
  }

  refundBroadcast(refundId: string, signedTx: string): Observable<FederationRefund> {
    return this.http.post<FederationRefund>(`${this.url}/refunds/${refundId}/broadcast`, { signedTx });
  }

  refundReconcile(refundId: string): Observable<FederationRefund> {
    return this.http.post<FederationRefund>(`${this.url}/refunds/${refundId}/reconcile`, {});
  }
}
