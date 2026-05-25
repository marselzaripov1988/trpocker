import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { AdminTournamentService } from '../services/admin-tournament.service';
import { TournamentSummaryApi } from '../../model/tournament-list.mapper';

@Component({
  selector: 'app-admin-tournament-list',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div class="admin-page" data-cy="admin-tournament-list">
      <header class="admin-header">
        <div>
          <h1>🛡️ Tournament moderation</h1>
          <p class="subtitle">All formats: Freezeout, SNG, MTT, Rebuy, Bounty, Pyramid</p>
        </div>
        <a routerLink="/admin/tournaments/create" class="btn-primary" data-cy="create-tournament-btn">
          + Create tournament
        </a>
      </header>

      @if (error()) {
        <div class="alert error" role="alert">{{ error() }}</div>
      }

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else {
        <section class="card">
          <h2>All tournaments ({{ tournaments().length }})</h2>
          @if (tournaments().length === 0) {
            <p class="muted">No tournaments yet.</p>
          } @else {
            <table class="data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Players</th>
                  <th>Buy-in</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (t of tournaments(); track t.id) {
                  <tr>
                    <td>{{ t.name }}</td>
                    <td><span class="badge">{{ t.type ?? '—' }}</span></td>
                    <td><span class="badge status">{{ t.status }}</span></td>
                    <td>{{ t.registeredPlayers }} / {{ t.maxPlayers }}</td>
                    <td>{{ t.buyIn }}</td>
                    <td>
                      <a [routerLink]="['/admin/tournaments', t.id]" class="btn-link">Manage →</a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
      }
    </div>
  `,
  styles: [`
    .admin-page { max-width: 1100px; margin: 0 auto; padding: 1.5rem; }
    .admin-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; margin-bottom: 1.5rem; }
    .admin-header h1 { margin: 0 0 0.25rem; }
    .subtitle { color: #94a3b8; margin: 0; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .card h2 { margin: 0 0 1rem; font-size: 1.1rem; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .data-table th, .data-table td { text-align: left; padding: 0.6rem 0.75rem; border-bottom: 1px solid #334155; }
    .data-table th { color: #94a3b8; font-weight: 600; }
    .badge { background: #334155; padding: 0.15rem 0.5rem; border-radius: 6px; font-size: 0.75rem; }
    .badge.status { background: #0f766e; }
    .btn-primary { background: #2563eb; color: #fff; padding: 0.6rem 1rem; border-radius: 8px; text-decoration: none; font-weight: 600; }
    .btn-link { color: #60a5fa; text-decoration: none; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .muted { color: #94a3b8; }
  `]
})
export class AdminTournamentListComponent implements OnInit {
  private readonly adminService = inject(AdminTournamentService);
  private readonly router = inject(Router);

  readonly tournaments = signal<TournamentSummaryApi[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.adminService.listTournaments('all').subscribe({
      next: list => {
        this.tournaments.set(list);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.message ?? 'Failed to load tournaments');
        this.loading.set(false);
      }
    });
  }
}
