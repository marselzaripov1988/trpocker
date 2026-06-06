import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CreateFederationRequest, FederationDetail } from '../models/admin-federation.models';

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

  distribute(id: string, shardBps: number): Observable<FederationDetail> {
    return this.http.post<FederationDetail>(`${this.url}/${id}/distribute?shardBps=${shardBps}`, {});
  }
}
