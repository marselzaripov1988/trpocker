import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';

import { CashService } from '../cash.service';
import { CashTable } from '../cash.models';
import { ErrorHandlerService } from '../../services/error-handler.service';

/** Cash-table lobby: browse open ring tables and sit down with a buy-in. */
@Component({
  selector: 'app-cash-lobby',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="lobby" data-cy="cash-lobby">
      <header class="head">
        <h1>💵 Cash tables</h1>
        <button class="btn ghost" (click)="load()" [disabled]="loading()">Refresh</button>
      </header>

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (tables().length === 0) {
        <p class="muted">No open cash tables right now.</p>
      } @else {
        <ul class="tables">
          @for (t of tables(); track t.id) {
            <li class="table" data-cy="cash-table-row">
              <div class="info">
                <strong class="name">{{ t.name }}</strong>
                <span class="stakes">{{ t.smallBlind }}/{{ t.bigBlind }} {{ t.asset }}</span>
                <span class="seats">{{ t.seatedPlayers }}/{{ t.maxSeats }} seated</span>
                <span class="buyin">Buy-in {{ t.minBuyIn }}–{{ t.maxBuyIn }} {{ t.asset }}</span>
              </div>
              <div class="actions">
                <input
                  type="number"
                  [min]="t.minBuyIn"
                  [max]="t.maxBuyIn"
                  [step]="t.bigBlind"
                  [(ngModel)]="buyIns[t.id]"
                  [placeholder]="t.minBuyIn"
                  data-cy="buyin-input" />
                <button class="btn" [disabled]="busy()" (click)="sit(t)" data-cy="sit-btn">Sit</button>
                <a class="btn ghost" [routerLink]="['/cash', t.id]">View</a>
              </div>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: [`
    .lobby { max-width: 760px; margin: 0 auto; padding: 1.5rem; }
    .head { display: flex; align-items: center; justify-content: space-between; }
    .tables { list-style: none; padding: 0; margin: 1rem 0 0; display: grid; gap: 0.75rem; }
    .table { display: flex; justify-content: space-between; align-items: center; gap: 1rem;
      background: #1e293b; border: 1px solid #334155; border-radius: 10px; padding: 0.9rem 1.1rem; }
    .info { display: flex; flex-direction: column; gap: 0.2rem; }
    .name { color: #f8fafc; }
    .stakes { color: #38bdf8; font-weight: 600; }
    .seats, .buyin { color: #94a3b8; font-size: 0.85rem; }
    .actions { display: flex; gap: 0.5rem; align-items: center; }
    .actions input { width: 90px; padding: 0.45rem; border-radius: 6px; border: 1px solid #475569;
      background: #0f172a; color: #f8fafc; }
    .btn { background: #2563eb; color: #fff; border: none; padding: 0.5rem 1rem; border-radius: 8px;
      cursor: pointer; font-weight: 600; text-decoration: none; }
    .btn.ghost { background: transparent; border: 1px solid #475569; color: #cbd5e1; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .muted { color: #94a3b8; }
  `]
})
export class CashLobbyComponent implements OnInit {
  private readonly service = inject(CashService);
  private readonly router = inject(Router);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly tables = signal<readonly CashTable[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);

  // Per-table chosen buy-in (bound via ngModel).
  buyIns: Record<string, number> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: tables => { this.tables.set(tables); this.loading.set(false); },
      error: () => { this.tables.set([]); this.loading.set(false); }
    });
  }

  sit(table: CashTable): void {
    const buyIn = this.buyIns[table.id] ?? table.minBuyIn;
    this.busy.set(true);
    this.service.sit(table.id, buyIn).subscribe({
      next: seat => {
        this.errorHandler.addSuccess('Seated', `Seat #${seat.seatNumber} with ${seat.stack} ${table.asset}`);
        this.busy.set(false);
        this.router.navigate(['/cash', table.id]);
      },
      error: () => {
        this.errorHandler.addError('Could not sit', 'Check your balance, the buy-in range, or table seats.');
        this.busy.set(false);
      }
    });
  }
}
