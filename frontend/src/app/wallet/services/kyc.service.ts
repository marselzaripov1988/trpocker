import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface KycStatusResponse {
  status: string;
}

/** Player-facing KYC API: read status + upload a verification video (user holding their passport). */
@Injectable({ providedIn: 'root' })
export class KycService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/v1/wallet/kyc`;

  getStatus(): Observable<KycStatusResponse> {
    return this.http.get<KycStatusResponse>(this.url);
  }

  uploadVerificationVideo(file: File): Observable<KycStatusResponse> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<KycStatusResponse>(`${this.url}/document`, form);
  }
}
