import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';

import { CashService } from '../cash.service';
import { CashTableState, PlayerAction } from '../cash.models';
import { AuthService } from '../../services/auth.service';
import { ErrorHandlerService } from '../../services/error-handler.service';

/** A live cash (ring) table: seats + the current hand, with deal / fold / check / call / raise / leave. */
@Component({
  selector: 'app-cash-table',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table-page" data-cy="cash-table">
      <a routerLink="/cash" class="back">← Lobby</a>

      @if (state(); as s) {
        <header class="head">
          <h1>{{ s.table.name }}</h1>
          <span class="stakes">{{ s.table.smallBlind }}/{{ s.table.bigBlind }} {{ s.table.asset }}</span>
          <button class="btn ghost" (click)="load()" [disabled]="busy()">Refresh</button>
        </header>

        <section class="seats">
          @for (seat of s.seats; track seat.seatNumber) {
            <div class="seat"
                 [class.acting]="s.hand.inProgress && seat.playerName === s.hand.currentActorName"
                 [class.you]="seat.playerName === myName()">
              <span class="num">#{{ seat.seatNumber }}</span>
              <span class="pname">{{ seat.playerName }}</span>
              <span class="stack">{{ seat.stack }} {{ s.table.asset }}</span>
              @if (seat.status !== 'ACTIVE') { <span class="badge">{{ seat.status }}</span> }
            </div>
          }
        </section>

        @if (s.hand.inProgress) {
          <section class="hand" data-cy="cash-hand">
            <div class="board">
              <span class="phase">{{ s.hand.phase }}</span>
              <span class="pot">Pot: {{ s.hand.pot }} {{ s.table.asset }}</span>
              <span class="cards">{{ s.hand.communityCards.length ? s.hand.communityCards.join('  ') : '—' }}</span>
            </div>
            @if (s.hand.yourCards.length) {
              <div class="your-cards" data-cy="your-cards">Your cards: <strong>{{ s.hand.yourCards.join('  ') }}</strong></div>
            }
            @if (isMyTurn()) {
              <div class="actions" data-cy="cash-actions">
                <button class="btn" [disabled]="busy()" (click)="act('FOLD')">Fold</button>
                <button class="btn" [disabled]="busy()" (click)="act('CHECK')">Check</button>
                <button class="btn" [disabled]="busy()" (click)="act('CALL')">Call</button>
                <input type="number" [min]="s.table.bigBlind" [step]="s.table.bigBlind"
                       [(ngModel)]="raiseAmount" placeholder="amount" />
                <button class="btn" [disabled]="busy()" (click)="act('RAISE', raiseAmount)">Raise</button>
              </div>
            } @else {
              <p class="muted">Waiting for {{ s.hand.currentActorName }}…</p>
            }
          </section>
        } @else {
          <section class="hand idle">
            <p class="muted">No hand in progress.</p>
            <button class="btn" [disabled]="busy() || s.seats.length < 2" (click)="deal()" data-cy="deal-btn">
              Deal
            </button>
          </section>
        }

        <footer class="foot">
          <button class="btn ghost" [disabled]="busy()" (click)="leave()" data-cy="leave-btn">Leave table</button>
        </footer>
      } @else if (loading()) {
        <p class="muted">Loading…</p>
      } @else {
        <p class="muted">Table not found.</p>
      }
    </div>
  `,
  styles: [`
    .table-page { max-width: 720px; margin: 0 auto; padding: 1.5rem; }
    .back { color: #60a5fa; text-decoration: none; }
    .head { display: flex; align-items: center; gap: 1rem; }
    .head h1 { margin: 0.3rem 0; flex: 1; }
    .stakes { color: #38bdf8; font-weight: 600; }
    .seats { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 0.6rem;
      margin: 1rem 0; }
    .seat { background: #1e293b; border: 1px solid #334155; border-radius: 10px; padding: 0.7rem;
      display: flex; flex-direction: column; gap: 0.2rem; }
    .seat.acting { border-color: #f59e0b; box-shadow: 0 0 0 1px #f59e0b inset; }
    .seat.you { background: #0b2545; }
    .num { color: #64748b; font-size: 0.75rem; }
    .pname { color: #f8fafc; font-weight: 600; }
    .stack { color: #34d399; }
    .badge { color: #fbbf24; font-size: 0.7rem; text-transform: uppercase; }
    .hand { background: #111827; border: 1px solid #334155; border-radius: 12px; padding: 1rem; }
    .board { display: flex; gap: 1rem; align-items: center; color: #cbd5e1; }
    .phase { color: #a78bfa; font-weight: 600; }
    .pot { color: #fcd34d; }
    .cards { letter-spacing: 0.15rem; font-size: 1.1rem; }
    .your-cards { margin-top: 0.6rem; color: #e2e8f0; letter-spacing: 0.1rem; }
    .actions { display: flex; gap: 0.5rem; align-items: center; margin-top: 0.8rem; flex-wrap: wrap; }
    .actions input { width: 90px; padding: 0.45rem; border-radius: 6px; border: 1px solid #475569;
      background: #0f172a; color: #f8fafc; }
    .foot { margin-top: 1rem; }
    .btn { background: #2563eb; color: #fff; border: none; padding: 0.5rem 1rem; border-radius: 8px;
      cursor: pointer; font-weight: 600; }
    .btn.ghost { background: transparent; border: 1px solid #475569; color: #cbd5e1; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .muted { color: #94a3b8; }
  `]
})
export class CashTableComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(CashService);
  private readonly auth = inject(AuthService);
  private readonly errorHandler = inject(ErrorHandlerService);

  private readonly user = toSignal(this.auth.currentUser$, { initialValue: null });
  readonly myName = computed(() => this.user()?.username ?? '');

  readonly state = signal<CashTableState | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);

  readonly isMyTurn = computed(() => {
    const h = this.state()?.hand;
    return !!h?.inProgress && h.currentActorName === this.myName();
  });

  raiseAmount: number | null = null;
  private id = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.state(this.id).subscribe({
      next: s => { this.state.set(s); this.loading.set(false); },
      error: () => { this.state.set(null); this.loading.set(false); }
    });
  }

  deal(): void {
    this.busy.set(true);
    this.service.deal(this.id).subscribe({
      next: s => { this.state.set(s); this.busy.set(false); },
      error: () => { this.errorHandler.addError('Could not deal', 'Need at least 2 seated players.'); this.busy.set(false); }
    });
  }

  act(action: PlayerAction, amount?: number | null): void {
    this.busy.set(true);
    this.service.act(this.id, action, amount ?? undefined).subscribe({
      next: result => {
        if (result.handComplete) {
          this.errorHandler.addInfo('Hand complete', `Rake ${result.totalRake} taken`);
        }
        this.busy.set(false);
        this.load();
      },
      error: () => { this.errorHandler.addError('Action rejected', 'It may not be your turn or the action is illegal.'); this.busy.set(false); this.load(); }
    });
  }

  leave(): void {
    this.busy.set(true);
    this.service.leave(this.id).subscribe({
      next: result => {
        this.errorHandler.addSuccess('Left the table',
          result.cashedOutNow ? `Cashed out ${result.amount}` : 'You will be cashed out at the end of the hand.');
        this.busy.set(false);
        this.router.navigate(['/cash']);
      },
      error: () => { this.errorHandler.addError('Could not leave', 'Try again.'); this.busy.set(false); }
    });
  }
}
