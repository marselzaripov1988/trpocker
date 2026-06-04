import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PoolStatus, PoolEntry, PoolImportResult } from '../models/pool.models';

/** Admin deposit-address pool: monitor free/assigned depth and import an offline-generated batch. */
@Injectable({ providedIn: 'root' })
export class AdminPoolService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/admin/wallet/deposit-pool`;

  status(): Observable<PoolStatus> {
    return this.http.get<PoolStatus>(`${this.url}/status`);
  }

  importBatch(addresses: PoolEntry[]): Observable<PoolImportResult> {
    return this.http.post<PoolImportResult>(`${this.url}/import`, { addresses });
  }
}
