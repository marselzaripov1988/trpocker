import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { AdminFederationService } from '../services/admin-federation.service';
import { FederationDetail } from '../models/admin-federation.models';
import { ErrorHandlerService } from '../../services/error-handler.service';

/**
 * Admin page for federated (sharded) pyramids: create one, then drive + monitor its lifecycle
 * (promote waves → run shards → schedule + start + run the final). Single-page: the create form on top,
 * the detail/lifecycle panel for the created (or refreshed) federation below.
 */
@Component({
  selector: 'app-admin-federation',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fed-page" data-cy="admin-federation">
      <h1>🔗 Federated pyramids</h1>
      <p class="hint">A very large field split into shards of <code>shardSize</code>; each shard is a pyramid
        run to one winner, and the winners meet in an admin-scheduled final.</p>

      <section class="card">
        <h2>Create</h2>
        <div class="form-grid">
          <label>Name <input data-cy="fed-name" [(ngModel)]="form.name" placeholder="Million Pyramid" /></label>
          <label>Starting players
            <input type="number" data-cy="fed-players" [(ngModel)]="form.startingPlayers" min="2" /></label>
          <label>Shard size
            <input type="number" data-cy="fed-shard" [(ngModel)]="form.shardSize" min="2" /></label>
          <label>Registration deadline (optional, blank = indefinite)
            <input type="datetime-local" [(ngModel)]="form.deadline" /></label>
          <label>House fee % (0–20, on the crypto prize pool)
            <input type="number" data-cy="fed-fee" [(ngModel)]="form.feePercent" min="0" max="20" step="0.5" /></label>
          <label class="check">
            <input type="checkbox" data-cy="fed-buyup" [(ngModel)]="form.buyUpEnabled" />
            Buy-up (real-money seat buy-outs in shards + final)
          </label>
          @if (form.buyUpEnabled) {
            <label>Buy-in amount
              <input type="number" data-cy="fed-buyin" [(ngModel)]="form.buyInAmount" min="0" /></label>
            <label>Buy-in asset
              <input data-cy="fed-asset" [(ngModel)]="form.buyInAsset" placeholder="USDT_TRC20" /></label>
          }
        </div>
        <button class="btn-primary" data-cy="fed-create" [disabled]="busy() || !canCreate()" (click)="create()">
          @if (busy()) { Working… } @else { Create federation }
        </button>
      </section>

      @if (federation(); as f) {
        <section class="card" data-cy="fed-detail">
          <div class="head">
            <h2>{{ f.name }}</h2>
            <span class="status" [class]="f.status.toLowerCase()">{{ f.status }}</span>
          </div>
          <div class="stats">
            <div><span>Shards</span><strong>{{ f.shardCount }}</strong></div>
            <div><span>Shard size</span><strong>{{ f.shardSize }}</strong></div>
            <div><span>Seats/table</span><strong>{{ f.seatsPerTable }}</strong></div>
            <div><span>Registered</span><strong>{{ f.registeredPlayers }}</strong></div>
            <div><span>House fee</span><strong>{{ (f.feeBasisPoints || 0) / 100 }}%</strong></div>
          </div>
          <div class="shardbar">
            <span class="chip pending">PENDING {{ f.shardsPending }}</span>
            <span class="chip registering">REGISTERING {{ f.shardsRegistering }}</span>
            <span class="chip ready">READY {{ f.shardsReady }}</span>
            <span class="chip running">RUNNING {{ f.shardsRunning }}</span>
            <span class="chip completed">COMPLETED {{ f.shardsCompleted }}</span>
          </div>
          @if (f.finalScheduledStart) {
            <p class="final">🏁 Final scheduled: <strong>{{ f.finalScheduledStart }}</strong></p>
          }
          @if (f.championPlayerId) {
            <p class="champ">🏆 Grand champion: <strong>{{ f.championPlayerId }}</strong></p>
          }

          <div class="actions">
            <button class="btn" [disabled]="busy()" (click)="act('promote')">Promote waves</button>
            <button class="btn" [disabled]="busy()" (click)="act('drain')">Run shards</button>
            <button class="btn" [disabled]="busy()" (click)="act('start-final')">Start final</button>
            <button class="btn" [disabled]="busy()" (click)="act('run-final')">Run final</button>
            <button class="btn-ghost" [disabled]="busy()" (click)="refresh()">↻ Refresh</button>
          </div>
          <div class="schedule">
            <input type="datetime-local" data-cy="fed-final-at" [(ngModel)]="scheduleAt" />
            <button class="btn" [disabled]="busy() || !scheduleAt" (click)="scheduleFinal()">
              Schedule final + e-mail finalists
            </button>
          </div>
          <div class="buyup" data-cy="fed-buyup-actions">
            <span class="hint">Buy-up controls (real-money buy-up federations only):</span>
            <div class="row">
              <input type="number" data-cy="fed-open-shard" [(ngModel)]="openShardIndex" min="0" />
              <button class="btn" [disabled]="busy()" (click)="openBuyUp()">Open shard for buy-up</button>
              <button class="btn" [disabled]="busy()" (click)="closeBuyUp()">Close buy-up + start</button>
            </div>
            <div class="row">
              <button class="btn" [disabled]="busy()" (click)="distribute()">Distribute prizes (final table)</button>
            </div>
          </div>
        </section>
      }
    </div>
  `,
  styles: [`
    .fed-page { max-width: 820px; margin: 0 auto; padding: 1.5rem; color: #e5e7eb; }
    .hint { color: #9ca3af; font-size: 0.9rem; }
    .card { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; padding: 1.25rem; margin-top: 1rem; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; margin-bottom: 1rem; }
    label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.85rem; color: #cbd5e1; }
    input { padding: 0.5rem; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15); background: rgba(0,0,0,0.25); color: #fff; }
    .head { display: flex; align-items: center; justify-content: space-between; }
    .status { padding: 0.2rem 0.6rem; border-radius: 999px; font-size: 0.75rem; background: rgba(99,102,241,0.25); color: #c7d2fe; }
    .status.completed { background: rgba(16,185,129,0.2); color: #34d399; }
    .status.cancelled { background: rgba(107,114,128,0.2); color: #9ca3af; }
    .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0.5rem; margin: 0.75rem 0; }
    .stats div { background: rgba(0,0,0,0.25); border-radius: 8px; padding: 0.5rem; text-align: center; }
    .stats span { display: block; font-size: 0.7rem; color: #9ca3af; }
    .shardbar { display: flex; flex-wrap: wrap; gap: 0.4rem; margin: 0.5rem 0; }
    .chip { font-size: 0.72rem; padding: 0.2rem 0.55rem; border-radius: 999px; background: rgba(0,0,0,0.3); }
    .chip.running { color: #fbbf24; } .chip.completed { color: #34d399; } .chip.ready { color: #93c5fd; }
    .actions, .schedule { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.75rem; align-items: center; }
    .check { flex-direction: row; align-items: center; gap: 0.4rem; grid-column: 1 / -1; }
    .buyup { margin-top: 0.75rem; border-top: 1px solid rgba(255,255,255,0.1); padding-top: 0.75rem; }
    .buyup .row { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; margin-top: 0.5rem; }
    .buyup input { width: 110px; }
    .btn-primary, .btn { background: linear-gradient(135deg,#6366f1,#4f46e5); color:#fff; border:none; border-radius:8px; padding:0.5rem 1rem; font-weight:600; cursor:pointer; }
    .btn-ghost { background: transparent; color:#cbd5e1; border:1px solid rgba(255,255,255,0.2); border-radius:8px; padding:0.5rem 1rem; cursor:pointer; }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    .final, .champ { color: #fbbf24; }
  `]
})
export class AdminFederationComponent {
  private readonly service = inject(AdminFederationService);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly form = {
    name: '', startingPlayers: 1000, shardSize: 100, deadline: '',
    buyUpEnabled: false, buyInAmount: 0, buyInAsset: 'USDT_TRC20', feePercent: 0
  };
  scheduleAt = '';
  openShardIndex = 0;

  readonly federation = signal<FederationDetail | null>(null);
  readonly busy = signal(false);

  canCreate(): boolean {
    return this.form.name.trim().length >= 3
      && this.form.startingPlayers >= this.form.shardSize
      && this.form.shardSize >= 2
      && this.form.feePercent >= 0 && this.form.feePercent <= 20;
  }

  create(): void {
    this.busy.set(true);
    this.service.create({
      name: this.form.name.trim(),
      startingPlayers: this.form.startingPlayers,
      shardSize: this.form.shardSize,
      registrationDeadline: this.form.deadline ? new Date(this.form.deadline).toISOString() : null,
      buyUpEnabled: this.form.buyUpEnabled,
      buyInAmount: this.form.buyUpEnabled ? this.form.buyInAmount : null,
      buyInAsset: this.form.buyUpEnabled ? this.form.buyInAsset : null,
      // House commission %, sent as basis points (e.g. 10% → 1000 bps), capped at 20% (2000 bps).
      feeBasisPoints: Math.round((this.form.feePercent || 0) * 100)
    }).subscribe({
      next: detail => {
        this.federation.set(detail);
        this.errorHandler.addSuccess('Federation created', `${detail.shardCount} shards`);
        this.busy.set(false);
      },
      error: () => {
        this.errorHandler.addError('Could not create the federation', 'Check the values and the feature flag.');
        this.busy.set(false);
      }
    });
  }

  act(action: 'promote' | 'drain' | 'start-final' | 'run-final'): void {
    const f = this.federation();
    if (!f) {
      return;
    }
    this.busy.set(true);
    const call = action === 'promote' ? this.service.promote(f.id)
      : action === 'drain' ? this.service.drainShards(f.id)
      : action === 'start-final' ? this.service.startFinal(f.id)
      : this.service.runFinal(f.id);
    call.subscribe({
      next: detail => this.applied(detail, action),
      error: () => this.failed(action)
    });
  }

  scheduleFinal(): void {
    const f = this.federation();
    if (!f || !this.scheduleAt) {
      return;
    }
    this.busy.set(true);
    this.service.scheduleFinal(f.id, new Date(this.scheduleAt).toISOString()).subscribe({
      next: detail => this.applied(detail, 'schedule-final'),
      error: () => this.failed('schedule-final')
    });
  }

  openBuyUp(): void {
    const f = this.federation();
    if (!f) {
      return;
    }
    this.busy.set(true);
    this.service.openShardForBuyUp(f.id, this.openShardIndex).subscribe({
      next: detail => this.applied(detail, `open-buyup shard ${this.openShardIndex}`),
      error: () => this.failed('open-buyup')
    });
  }

  closeBuyUp(): void {
    const f = this.federation();
    if (!f) {
      return;
    }
    this.busy.set(true);
    this.service.closeBuyUp(f.id).subscribe({
      next: detail => this.applied(detail, 'close-buyup'),
      error: () => this.failed('close-buyup')
    });
  }

  distribute(): void {
    const f = this.federation();
    if (!f) {
      return;
    }
    this.busy.set(true);
    this.service.distribute(f.id).subscribe({
      next: detail => this.applied(detail, 'distribute (final table)'),
      error: () => this.failed('distribute')
    });
  }

  refresh(): void {
    const f = this.federation();
    if (!f) {
      return;
    }
    this.busy.set(true);
    this.service.get(f.id).subscribe({
      next: detail => { this.federation.set(detail); this.busy.set(false); },
      error: () => this.failed('refresh')
    });
  }

  private applied(detail: FederationDetail, action: string): void {
    this.federation.set(detail);
    this.errorHandler.addSuccess(`Done: ${action}`, `Status: ${detail.status}`);
    this.busy.set(false);
  }

  private failed(action: string): void {
    this.errorHandler.addError(`Action failed: ${action}`, 'The federation may not be in a valid state for it.');
    this.busy.set(false);
  }
}
