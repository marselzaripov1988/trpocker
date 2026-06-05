import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CreateTournamentAdminRequest, PyramidRunResponse } from '../models/admin-tournament.models';
import { TournamentDetailApi } from '../../model/tournament-detail.mapper';
import { TournamentSummaryApi } from '../../model/tournament-list.mapper';

@Injectable({ providedIn: 'root' })
export class AdminTournamentService {
  private readonly http = inject(HttpClient);
  private readonly adminUrl = `${environment.apiUrl}/v1/admin/tournaments`;
  private readonly publicUrl = `${environment.apiUrl}/v1/tournaments`;

  listTournaments(status = 'all'): Observable<TournamentSummaryApi[]> {
    return this.http.get<TournamentSummaryApi[]>(`${this.publicUrl}?status=${status}`);
  }

  getTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.get<TournamentDetailApi>(`${this.publicUrl}/${id}`);
  }

  createTournament(request: CreateTournamentAdminRequest): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(this.adminUrl, request);
  }

  startTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/start`, {});
  }

  pauseTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/pause`, {});
  }

  resumeTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/resume`, {});
  }

  endTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/end`, {});
  }

  cancelTournament(id: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/cancel`, {});
  }

  /** Schedule an absolute auto-start time (ISO instant). REGISTERING only. */
  scheduleStart(id: string, startAt: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/schedule`, { startAt });
  }

  /** Pin to a time-of-day slot; requireFull = start only if the table is full (else postpone a day). */
  scheduleDaily(id: string, timeOfDay: string, requireFull: boolean): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${id}/schedule-daily`, {
      timeOfDay,
      requireFull
    });
  }

  eliminatePlayer(tournamentId: string, playerId: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(
      `${this.adminUrl}/${tournamentId}/eliminate/${playerId}`,
      {}
    );
  }

  registerBots(tournamentId: string, count: number, namePrefix = 'Bot_'): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${tournamentId}/register-bots`, {
      count,
      namePrefix
    });
  }

  playPyramidRound(tournamentId: string): Observable<TournamentDetailApi> {
    return this.http.post<TournamentDetailApi>(`${this.adminUrl}/${tournamentId}/pyramid/round`, {});
  }

  runPyramidToCompletion(tournamentId: string): Observable<PyramidRunResponse> {
    return this.http.post<PyramidRunResponse>(`${this.adminUrl}/${tournamentId}/pyramid/run`, {});
  }
}
