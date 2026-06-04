import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { KycDecision, KycPendingItem } from '../models/kyc.models';

/** Admin KYC moderation API: list pending, stream a user's verification video, decide, and erase (GDPR). */
@Injectable({ providedIn: 'root' })
export class AdminKycService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/admin/wallet/kyc`;

  listPending(): Observable<KycPendingItem[]> {
    return this.http.get<KycPendingItem[]>(`${this.url}/pending`);
  }

  /** Download the user's latest verification video as a Blob (the auth interceptor adds the token). */
  loadVideo(userId: string): Observable<Blob> {
    return this.http.get(`${this.url}/${userId}/document`, { responseType: 'blob' });
  }

  decide(userId: string, status: KycDecision, note?: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.url}/${userId}/decision`, { status, note: note ?? null });
  }

  erase(userId: string): Observable<{ erased: number }> {
    return this.http.delete<{ erased: number }>(`${this.url}/${userId}/documents`);
  }

  /** Re-encrypt all KYC media under the active key/provider (key rotation / KMS migration). */
  reEncrypt(): Observable<KycReEncryptResult> {
    return this.http.post<KycReEncryptResult>(`${this.url}/re-encrypt`, {});
  }
}

export interface KycReEncryptResult {
  reEncrypted: number;
  skipped: number;
  total: number;
}
