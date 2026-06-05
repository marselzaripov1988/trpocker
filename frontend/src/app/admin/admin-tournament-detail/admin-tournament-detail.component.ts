import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { AdminTournamentService } from '../services/admin-tournament.service';
import { TournamentDetailApi } from '../../model/tournament-detail.mapper';

@Component({
  selector: 'app-admin-tournament-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  template: `
    <div class="admin-page" data-cy="admin-tournament-detail">
      <header class="admin-header">
        <a routerLink="/admin/tournaments" class="back">← All tournaments</a>
        @if (detail()) {
          <h1>{{ detail()!.name }}</h1>
          <p class="meta">
            <span class="badge">{{ detail()!.type ?? '—' }}</span>
            <span class="badge status">{{ detail()!.status }}</span>
            {{ detail()!.registeredPlayers }} / {{ detail()!.maxPlayers }} registered ·
            {{ detail()!.playersRemaining }} remaining
          </p>
        }
      </header>

      @if (message()) {
        <div class="alert ok">{{ message() }}</div>
      }
      @if (error()) {
        <div class="alert error">{{ error() }}</div>
      }

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (detail(); as d) {
        <div class="grid">
          <section class="card">
            <h2>Lifecycle</h2>
            <div class="btn-row">
              @if (d.status === 'REGISTERING') {
                <button class="btn-primary" (click)="act('start')" [disabled]="busy()">▶ Start</button>
                <button class="btn-danger" (click)="act('cancel')" [disabled]="busy()">✕ Cancel</button>
              }
              @if (d.status === 'RUNNING' || d.status === 'LATE_REGISTRATION' || d.status === 'FINAL_TABLE' || d.status === 'HEADS_UP') {
                <button class="btn-secondary" (click)="act('pause')" [disabled]="busy()">⏸ Pause</button>
                <button class="btn-danger" (click)="act('end')" [disabled]="busy()">🏁 End</button>
              }
              @if (d.status === 'PAUSED') {
                <button class="btn-primary" (click)="act('resume')" [disabled]="busy()">▶ Resume</button>
                <button class="btn-danger" (click)="act('end')" [disabled]="busy()">🏁 End</button>
              }
              @if (d.status === 'STARTING') {
                <span class="muted">Starting (async)… refresh shortly.</span>
              }
              @if (d.status === 'COMPLETED' || d.status === 'FINISHED') {
                <span class="muted">Tournament finished.</span>
              }
              @if (d.status === 'CANCELLED') {
                <span class="muted">Tournament cancelled.</span>
              }
            </div>
            <a [routerLink]="['/tournaments', tournamentId]" target="_blank" class="player-link">
              Open player lobby ↗
            </a>
          </section>

          @if (d.status === 'REGISTERING') {
            <section class="card">
              <h2>⏰ Schedule auto-start</h2>
              @if (d.scheduledStart) {
                <p class="muted">
                  Scheduled: <strong>{{ d.scheduledStart | date:'medium' }}</strong>
                  @if (d.requireFullToStart) { <span class="badge">requires full table</span> }
                </p>
              } @else {
                <p class="muted">Not scheduled (manual start).</p>
              }
              <div class="row-inline">
                <select [(ngModel)]="scheduleMode">
                  <option value="datetime">Exact date &amp; time</option>
                  <option value="timeofday">Daily time-of-day</option>
                </select>
                @if (scheduleMode === 'datetime') {
                  <input type="datetime-local" [(ngModel)]="scheduleAt" />
                } @else {
                  <input type="time" [(ngModel)]="scheduleTime" />
                  <label class="chk">
                    <input type="checkbox" [(ngModel)]="requireFull" /> start only if full (else next day)
                  </label>
                }
                <button class="btn-primary" (click)="schedule()" [disabled]="busy() || !scheduleValid()">
                  Schedule
                </button>
              </div>
              <p class="hint">
                Time-of-day uses the server zone; the first slot leaves a registration runway (else next day).
                Auto-start fires only when the scheduled-start poller is enabled on the server.
              </p>
            </section>
          }

          @if (isPyramid()) {
            <section class="card">
              <h2>Pyramid controls</h2>
              <p class="hint">Survival format: chip leader per table advances each round.</p>
              <div class="btn-row">
                <button class="btn-secondary" (click)="act('pyramidRound')" [disabled]="busy() || !canPyramidAct()">
                  Advance one round
                </button>
                <button class="btn-warn" (click)="act('pyramidRun')" [disabled]="busy() || !canPyramidAct()">
                  Run to completion (bots)
                </button>
              </div>
            </section>
          }

          <section class="card">
            <h2>Batch bots (testing)</h2>
            <div class="row-inline">
              <input type="number" [(ngModel)]="botCount" min="1" max="10000" />
              <input type="text" [(ngModel)]="botPrefix" placeholder="Bot_" />
              <button class="btn-secondary" (click)="act('registerBots')" [disabled]="busy() || d.status !== 'REGISTERING'">
                Register bots
              </button>
            </div>
          </section>

          <section class="card">
            <h2>Eliminate player</h2>
            <div class="row-inline">
              <select [(ngModel)]="eliminatePlayerId">
                <option value="">— Select player —</option>
                @for (p of activePlayers(); track p.playerId) {
                  <option [value]="p.playerId">{{ p.playerName }} ({{ p.chips }} chips)</option>
                }
              </select>
              <button class="btn-danger" (click)="act('eliminate')" [disabled]="busy() || !eliminatePlayerId">
                Eliminate
              </button>
            </div>
          </section>

          <section class="card wide">
            <h2>Tables ({{ d.tableCount ?? d.tables?.length ?? 0 }})</h2>
            @if (!d.tables?.length) {
              <p class="muted">No table data (large field uses leaderboard API).</p>
            } @else {
              <ul class="table-list">
                @for (t of d.tables; track t.id) {
                  <li>Table {{ t.tableNumber }} — {{ t.playerCount }} players
                    @if (t.isFinalTable) { <span class="badge">Final</span> }
                  </li>
                }
              </ul>
            }
          </section>

          <section class="card wide">
            <h2>Standings</h2>
            @if (!d.players?.length) {
              <p class="muted">Use public leaderboard for large tournaments.</p>
            } @else {
              <table class="data-table">
                <thead>
                  <tr><th>#</th><th>Player</th><th>Chips</th><th>Status</th></tr>
                </thead>
                <tbody>
                  @for (p of d.players; track p.playerId) {
                    <tr>
                      <td>{{ p.rank }}</td>
                      <td>{{ p.playerName }}</td>
                      <td>{{ p.chips }}</td>
                      <td>{{ p.status }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </section>
        </div>
      }
    </div>
  `,
  styles: [`
    .admin-page { max-width: 1000px; margin: 0 auto; padding: 1.5rem; }
    .back { color: #60a5fa; text-decoration: none; }
    .admin-header h1 { margin: 0.5rem 0 0.25rem; }
    .meta { color: #94a3b8; margin: 0; display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
    .card.wide { grid-column: 1 / -1; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .card h2 { margin: 0 0 0.75rem; font-size: 1rem; }
    .btn-row { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 0.75rem; }
    .row-inline { display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
    .row-inline input, .row-inline select { padding: 0.45rem; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #f8fafc; }
    .btn-primary { background: #2563eb; color: #fff; border: none; padding: 0.5rem 0.9rem; border-radius: 8px; cursor: pointer; }
    .btn-secondary { background: #475569; color: #fff; border: none; padding: 0.5rem 0.9rem; border-radius: 8px; cursor: pointer; }
    .btn-danger { background: #b91c1c; color: #fff; border: none; padding: 0.5rem 0.9rem; border-radius: 8px; cursor: pointer; }
    .btn-warn { background: #b45309; color: #fff; border: none; padding: 0.5rem 0.9rem; border-radius: 8px; cursor: pointer; }
    button:disabled { opacity: 0.45; cursor: not-allowed; }
    .badge { background: #334155; padding: 0.15rem 0.5rem; border-radius: 6px; font-size: 0.75rem; }
    .badge.status { background: #0f766e; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .alert.ok { background: #14532d; color: #bbf7d0; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .hint, .muted { color: #94a3b8; font-size: 0.85rem; }
    .player-link { color: #60a5fa; font-size: 0.9rem; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    .data-table th, .data-table td { padding: 0.4rem 0.6rem; border-bottom: 1px solid #334155; text-align: left; }
    .table-list { margin: 0; padding-left: 1.2rem; color: #cbd5e1; }
  `]
})
export class AdminTournamentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly adminService = inject(AdminTournamentService);

  tournamentId = '';
  readonly detail = signal<TournamentDetailApi | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);

  botCount = 10;
  botPrefix = 'Bot_';
  eliminatePlayerId = '';

  scheduleMode: 'datetime' | 'timeofday' = 'datetime';
  scheduleAt = '';   // datetime-local value (local time)
  scheduleTime = ''; // HH:mm
  requireFull = false;

  readonly isPyramid = computed(() => this.detail()?.type === 'PYRAMID');

  readonly activePlayers = computed(() => {
    const players = this.detail()?.players ?? [];
    return players.filter(p => p.status === 'PLAYING' || p.status === 'REGISTERED');
  });

  ngOnInit(): void {
    this.tournamentId = this.route.snapshot.paramMap.get('id') ?? '';
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.adminService.getTournament(this.tournamentId).subscribe({
      next: d => {
        this.detail.set(d);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.message ?? 'Load failed');
        this.loading.set(false);
      }
    });
  }

  canPyramidAct(): boolean {
    const s = this.detail()?.status;
    return s === 'RUNNING' || s === 'PAUSED' || s === 'REGISTERING';
  }

  scheduleValid(): boolean {
    return this.scheduleMode === 'datetime' ? !!this.scheduleAt : !!this.scheduleTime;
  }

  schedule(): void {
    if (!this.scheduleValid()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.message.set(null);
    const done = (msg: string) => { this.message.set(msg); this.busy.set(false); this.reload(); };
    const fail = (err: { error?: { message?: string } }) => {
      this.error.set(err?.error?.message ?? 'Schedule failed');
      this.busy.set(false);
    };
    if (this.scheduleMode === 'datetime') {
      const iso = new Date(this.scheduleAt).toISOString(); // local datetime-local → UTC instant
      this.adminService.scheduleStart(this.tournamentId, iso)
        .subscribe({ next: () => done('Scheduled'), error: fail });
    } else {
      this.adminService.scheduleDaily(this.tournamentId, this.scheduleTime, this.requireFull)
        .subscribe({ next: () => done('Scheduled (time-of-day)'), error: fail });
    }
  }

  act(action: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.message.set(null);

    const done = (msg: string) => {
      this.message.set(msg);
      this.busy.set(false);
      this.reload();
    };
    const fail = (err: { error?: { message?: string } }) => {
      this.error.set(err?.error?.message ?? 'Action failed');
      this.busy.set(false);
    };

    switch (action) {
      case 'start':
        this.adminService.startTournament(this.tournamentId).subscribe({ next: () => done('Tournament started'), error: fail });
        break;
      case 'pause':
        this.adminService.pauseTournament(this.tournamentId).subscribe({ next: () => done('Paused'), error: fail });
        break;
      case 'resume':
        this.adminService.resumeTournament(this.tournamentId).subscribe({ next: () => done('Resumed'), error: fail });
        break;
      case 'end':
        this.adminService.endTournament(this.tournamentId).subscribe({ next: () => done('Tournament ended'), error: fail });
        break;
      case 'cancel':
        this.adminService.cancelTournament(this.tournamentId).subscribe({ next: () => done('Cancelled'), error: fail });
        break;
      case 'eliminate':
        if (!this.eliminatePlayerId) return;
        this.adminService.eliminatePlayer(this.tournamentId, this.eliminatePlayerId).subscribe({
          next: () => { this.eliminatePlayerId = ''; done('Player eliminated'); },
          error: fail
        });
        break;
      case 'registerBots':
        this.adminService.registerBots(this.tournamentId, this.botCount, this.botPrefix).subscribe({
          next: () => done(`Registered ${this.botCount} bots`),
          error: fail
        });
        break;
      case 'pyramidRound':
        this.adminService.playPyramidRound(this.tournamentId).subscribe({
          next: () => done('Pyramid round completed'),
          error: fail
        });
        break;
      case 'pyramidRun':
        this.adminService.runPyramidToCompletion(this.tournamentId).subscribe({
          next: r => {
            this.message.set(`Pyramid finished: champion ${r.championId}, ${r.roundsPlayed} rounds`);
            this.busy.set(false);
            this.reload();
          },
          error: fail
        });
        break;
      default:
        this.busy.set(false);
    }
  }
}
