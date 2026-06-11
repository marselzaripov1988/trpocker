import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';

import { FederationService } from '../federation.service';
import { FederationDetail, FederationRegistration, FinalSeat } from '../federation.models';
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

        @if (prizeInfo(); as p) {
          <details class="prizes" data-cy="fed-prizes">
            <summary>💰 How the prize pool is split</summary>
            @if (p.realMoney) {
              <p class="muted">
                Guaranteed pool if the field fills:
                <strong>{{ p.gross | number:'1.0-2' }} {{ p.asset }}</strong>
                @if (p.feePct > 0) {
                  <span class="fine">(minus {{ p.feePct | number:'1.0-2' }}% organiser fee → {{ p.net | number:'1.0-2' }} {{ p.asset }} paid out)</span>
                }
              </p>
            } @else {
              <p class="muted">Shown as a share of the prize pool (play-money). The organiser may take up to 20% commission.</p>
            }
            <ul>
              @for (r of p.rows; track r.label) {
                <li>
                  <span>{{ r.label }}@if (r.count > 1) { <em class="cnt">×{{ r.count }}</em> }</span>
                  <strong>
                    @if (r.amount !== null) { {{ r.amount | number:'1.0-4' }} {{ p.asset }}&nbsp; }
                    <em class="pct">{{ r.pct | number:'1.0-4' }}%</em>
                  </strong>
                </li>
              }
            </ul>
            <p class="fine">
              Percentages are this federation's live config. The grand champion takes whatever remains after every
              other payout, so the pool is always paid out in full.
            </p>
          </details>
        }

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

        @if (finalSeats().length > 0) {
          <section class="seats" data-cy="final-seats">
            <h2>🎟️ Buy a guaranteed final seat</h2>
            <p class="muted">Skip the shards — claim a finalist seat directly (closes that empty shard).</p>
            <ul>
              @for (s of finalSeats(); track s.shardIndex) {
                <li>
                  <span>Shard #{{ s.shardIndex }}</span>
                  <span class="price">{{ s.price | number }} {{ s.asset }}</span>
                  <button class="btn" [disabled]="busy()" (click)="buyFinal(s)" data-cy="buy-final">Buy</button>
                </li>
              }
            </ul>
          </section>
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
    .seats { margin: 1rem 0; }
    .seats ul { list-style: none; margin: 0.5rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.4rem; }
    .seats li { display: flex; align-items: center; gap: 0.75rem; background: rgba(0,0,0,0.25); border-radius: 8px; padding: 0.5rem 0.75rem; }
    .seats .price { color: #fbbf24; margin-left: auto; }
    .prizes { margin: 1rem 0; background: rgba(0,0,0,0.25); border: 1px solid rgba(99,102,241,0.25); border-radius: 8px; padding: 0.75rem 1rem; }
    .prizes summary { cursor: pointer; font-weight: 600; color: #fbbf24; }
    .prizes ul { list-style: none; margin: 0.6rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.35rem; }
    .prizes li { display: flex; align-items: center; gap: 0.75rem; }
    .prizes li strong { margin-left: auto; color: #e5e7eb; text-align: right; }
    .prizes .cnt { font-style: normal; font-size: 0.7rem; color: #9ca3af; margin-left: 0.3rem; }
    .prizes .pct { font-style: normal; font-size: 0.72rem; color: #9ca3af; }
    .prizes .fine { font-size: 0.75rem; color: #6b7280; margin: 0.6rem 0 0; }
    .btn { background: linear-gradient(135deg,#6366f1,#4f46e5); color:#fff; border:none; border-radius:8px; padding:0.4rem 1rem; font-weight:600; cursor:pointer; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  `]
})
export class FederationViewComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(FederationService);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly federation = signal<FederationDetail | null>(null);
  readonly myRegistration = signal<FederationRegistration | null>(null);
  readonly finalSeats = signal<readonly FinalSeat[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);

  readonly canRegister = computed(() => {
    const s = this.federation()?.status;
    return s === 'REGISTERING' || s === 'SHARDS_RUNNING';
  });

  /**
   * Live prize breakdown derived from the federation's effective config: dynamic percentages, and — for a
   * real-money federation — currency amounts off the GUARANTEED pool ({@code shardCount × shardSize × buyIn}),
   * net of the organiser fee. Each row is one player's payout at that finish (champion, places, each rest seat,
   * each shard winner). The champion's share is the remainder so everything sums to 100% of the net pool.
   */
  readonly prizeInfo = computed(() => {
    const f = this.federation();
    if (!f) {
      return null;
    }
    const placeBps = (f.finalTablePlaceBps ?? '')
      .split(',').map(s => parseInt(s.trim(), 10)).filter(n => !isNaN(n));
    const restBps = f.finalTableRestBps ?? 0;
    const ppm = f.shardWinnerPpm ?? 0;
    const feeBps = f.feeBasisPoints ?? 0;
    const nonChampionSeats = Math.max(0, f.seatsPerTable - 1);
    const restCount = Math.max(0, nonChampionSeats - placeBps.length);
    const placeSum = placeBps.reduce((a, b) => a + b, 0);
    const shardQualifierBps = (f.shardCount * ppm) / 100; // ppm → bps: 1 ppm = 0.01 bps
    const championBps = Math.max(
      0, 10000 - placeSum - (restCount > 0 ? restBps : 0) - shardQualifierBps);

    const buyIn = f.cryptoBuyInAmount ?? null;
    const realMoney = buyIn != null && buyIn > 0;
    const gross = realMoney ? f.shardCount * f.shardSize * (buyIn as number) : 0;
    const net = gross * (1 - feeBps / 10000);
    const amt = (bps: number) => (realMoney ? (net * bps) / 10000 : null);

    const ordinals = ['2nd', '3rd', '4th', '5th', '6th', '7th', '8th', '9th', '10th'];
    const rows: { label: string; pct: number; amount: number | null; count: number }[] = [];
    rows.push({ label: '🏆 Grand champion', pct: championBps / 100, amount: amt(championBps), count: 1 });
    placeBps.forEach((bps, i) => {
      const medal = i === 0 ? '🥈' : i === 1 ? '🥉' : '🎯';
      rows.push({ label: `${medal} ${ordinals[i] ?? `${i + 2}th`} place`, pct: bps / 100, amount: amt(bps), count: 1 });
    });
    if (restCount > 0 && restBps > 0) {
      const each = restBps / restCount;
      rows.push({ label: 'Rest of the final table (each)', pct: each / 100, amount: amt(each), count: restCount });
    }
    if (ppm > 0) {
      // Single denominator for the whole table: the qualifier is ppm of the WHOLE federation pool (e.g. 100 ppm
      // = 0.01%), so every row's percentage is comparable and the column sums to 100%.
      rows.push({ label: 'Each shard winner', pct: ppm / 10000, amount: amt(ppm / 100), count: f.shardCount });
    }

    return {
      realMoney, asset: f.cryptoBuyInAsset ?? '', feePct: feeBps / 100, gross, net, rows,
    };
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

  buyFinal(seat: FinalSeat): void {
    this.busy.set(true);
    this.service.buyFinalSeat(this.id, seat.shardIndex).subscribe({
      next: () => {
        this.errorHandler.addSuccess('Final seat bought', `Shard #${seat.shardIndex} for ${seat.price} ${seat.asset}`);
        this.busy.set(false);
        this.load();
      },
      error: () => {
        this.errorHandler.addError('Could not buy the final seat', 'It may no longer be available.');
        this.busy.set(false);
        this.load();
      }
    });
  }

  private load(): void {
    this.loading.set(true);
    this.service.get(this.id).subscribe({
      next: detail => { this.federation.set(detail); this.loading.set(false); },
      error: () => { this.federation.set(null); this.loading.set(false); }
    });
    // Best-effort: final seats exist only for buy-up federations (the endpoint errors otherwise).
    this.service.finalSeats(this.id).subscribe({
      next: seats => this.finalSeats.set(seats),
      error: () => this.finalSeats.set([])
    });
  }
}
