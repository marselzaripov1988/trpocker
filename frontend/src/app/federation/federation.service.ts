import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { FederationDetail, FederationRegistration } from './federation.models';

/** Player-facing federated pyramid API: read status + register (assigned to a shard). */
@Injectable({ providedIn: 'root' })
export class FederationService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/pyramid-federations`;

  get(id: string): Observable<FederationDetail> {
    return this.http.get<FederationDetail>(`${this.url}/${id}`);
  }

  register(id: string): Observable<FederationRegistration> {
    return this.http.post<FederationRegistration>(`${this.url}/${id}/register`, {});
  }
}
