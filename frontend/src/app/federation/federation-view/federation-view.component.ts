import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';

import { FederationService } from '../federation.service';
import { FederationDetail, FederationRegistration } from '../federation.models';
import { ErrorHandlerService } from '../../services/error-handler.service';

/**
 * Player view of a federated pyramid: shows the field's status + shard progress, lets the player register
 * (they're assigned to a shard by fill order), and once registered shows their shard + standing.
 */
@Component({
  selector: 'app-federation-view',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fed" data-cy="federation-view">
      @if (federation(); as f) {
        <header class="head">
          <h1>{{ f.name }}</h1>
          <span class="status" [class]="f.status.toLowerCase()">{{ f.status }}</span>
        </header>
        <p class="sub">{{ f.shardCount }} shards × {{ f.shardSize }} players → one grand champion</p>

        <div class="stats">
          <div><span>Registered</span><strong>{{ f.registeredPlayers | number }}</strong></div>
          <div><span>Shards running</span><strong>{{ f.shardsRunning }}</strong></div>
          <div><span>Shards done</span><strong>{{ f.shardsCompleted }}/{{ f.shardCount }}</strong></div>
        </div>

        @if (myRegistration(); as r) {
          <div class="mine" data-cy="my-shard">
            ✅ You're registered — <strong>shard #{{ r.shardIndex }}</strong> ({{ r.shardStatus }})
          </div>
        } @else if (canRegister()) {
          <button class="btn" data-cy="fed-register" [disabled]="busy()" (click)="register()">
            @if (busy()) { Registering… } @else { Register }
          </button>
        } @else {
          <p class="closed">Registration is closed for this federation.</p>
        }

        @if (f.finalScheduledStart) {
          <p class="final">🏁 Final scheduled: <strong>{{ f.finalScheduledStart }}</strong></p>
        }
        @if (f.championPlayerId) {
          <p class="champ">🏆 Grand champion: <strong>{{ f.championPlayerId }}</strong></p>
        }
      } @else if (loading()) {
        <p class="muted">Loading…</p>
      } @else {
        <p class="muted">Federation not found.</p>
      }
    </div>
  `,
  styles: [`
    .fed { max-width: 640px; margin: 0 auto; padding: 1.5rem; color: #e5e7eb; }
    .head { display: flex; align-items: center; gap: 0.75rem; }
    .status { padding: 0.2rem 0.6rem; border-radius: 999px; font-size: 0.75rem; background: rgba(99,102,241,0.25); color: #c7d2fe; }
    .status.completed { background: rgba(16,185,129,0.2); color: #34d399; }
    .sub { color: #9ca3af; }
    .stats { display: grid; grid-template-columns: repeat(3,1fr); gap: 0.5rem; margin: 1rem 0; }
    .stats div { background: rgba(0,0,0,0.25); border-radius: 8px; padding: 0.6rem; text-align: center; }
    .stats span { display: block; font-size: 0.7rem; color: #9ca3af; }
    .mine { background: rgba(16,185,129,0.15); border: 1px solid rgba(16,185,129,0.4); border-radius: 8px; padding: 0.75rem; }
    .btn { background: linear-gradient(135deg,#6366f1,#4f46e5); color:#fff; border:none; border-radius:8px; padding:0.6rem 1.4rem; font-weight:600; cursor:pointer; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .closed, .muted { color: #9ca3af; }
    .final, .champ { color: #fbbf24; }
  `]
})
export class FederationViewComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(FederationService);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly federation = signal<FederationDetail | null>(null);
  readonly myRegistration = signal<FederationRegistration | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);

  readonly canRegister = computed(() => {
    const s = this.federation()?.status;
    return s === 'REGISTERING' || s === 'SHARDS_RUNNING';
  });

  private id = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  register(): void {
    this.busy.set(true);
    this.service.register(this.id).subscribe({
      next: reg => {
        this.myRegistration.set(reg);
        this.errorHandler.addSuccess('Registered', `You're in shard #${reg.shardIndex}`);
        this.busy.set(false);
        this.load();
      },
      error: () => {
        this.errorHandler.addError('Could not register', 'Registration may be closed or full.');
        this.busy.set(false);
      }
    });
  }

  private load(): void {
    this.loading.set(true);
    this.service.get(this.id).subscribe({
      next: detail => { this.federation.set(detail); this.loading.set(false); },
      error: () => { this.federation.set(null); this.loading.set(false); }
    });
  }
}
