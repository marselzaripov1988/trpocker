import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { AdminCashService, CreateCashTableRequest } from '../services/admin-cash.service';
import { CashTable } from '../../cash/cash.models';
import { ErrorHandlerService } from '../../services/error-handler.service';

/**
 * Admin page for real-money cash (ring) tables: create a table (stakes, buy-in range, seats, rake) and see the
 * currently active tables. The backend exposes only create on the admin path; the listing reuses the
 * player-facing active-tables endpoint.
 */
@Component({
  selector: 'app-admin-cash',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cash-admin" data-cy="admin-cash">
      <h1>🪙 Cash tables</h1>
      <p class="hint">Real-money ring tables: set the blinds, buy-in range, seats, and rake. Players sit down
        from the cash lobby.</p>

      <section class="card">
        <h2>Create table</h2>
        <div class="form-grid">
          <label>Name
            <input data-cy="ct-name" [(ngModel)]="form.name" placeholder="Nightly NL Hold'em" /></label>
          <label>Asset
            <select data-cy="ct-asset" [(ngModel)]="form.asset">
              @for (a of assets; track a) { <option [value]="a">{{ a }}</option> }
            </select></label>
          <label>Small blind
            <input type="number" step="0.0001" min="0" data-cy="ct-sb" [(ngModel)]="form.smallBlind" /></label>
          <label>Big blind
            <input type="number" step="0.0001" min="0" data-cy="ct-bb" [(ngModel)]="form.bigBlind" /></label>
          <label>Min buy-in
            <input type="number" step="0.0001" min="0" data-cy="ct-minbuy" [(ngModel)]="form.minBuyIn" /></label>
          <label>Max buy-in
            <input type="number" step="0.0001" min="0" data-cy="ct-maxbuy" [(ngModel)]="form.maxBuyIn" /></label>
          <label>Seats
            <input type="number" min="2" max="10" data-cy="ct-seats" [(ngModel)]="form.maxSeats" /></label>
          <label>Rake (basis points)
            <input type="number" min="0" max="10000" data-cy="ct-rakebps" [(ngModel)]="form.rakeBasisPoints" /></label>
          <label>Rake cap
            <input type="number" step="0.0001" min="0" data-cy="ct-rakecap" [(ngModel)]="form.rakeCap" /></label>
        </div>
        <button class="btn-primary" data-cy="ct-create" [disabled]="busy() || !canCreate()" (click)="create()">
          @if (busy()) { Working… } @else { Create table }
        </button>
      </section>

      <section class="card">
        <div class="list-head">
          <h2>Active tables ({{ tables().length }})</h2>
          <button class="btn-ghost" [disabled]="busy()" (click)="refresh()" data-cy="ct-refresh">↻ Refresh</button>
        </div>
        @if (tables().length === 0) {
          <p class="empty" data-cy="ct-empty">No active cash tables yet.</p>
        } @else {
          <table class="tables" data-cy="ct-list">
            <thead>
              <tr><th>Name</th><th>Asset</th><th>Blinds</th><th>Buy-in</th><th>Seats</th><th>Rake</th></tr>
            </thead>
            <tbody>
              @for (t of tables(); track t.id) {
                <tr data-cy="ct-row">
                  <td>{{ t.name }}</td>
                  <td>{{ t.asset }}</td>
                  <td>{{ t.smallBlind }} / {{ t.bigBlind }}</td>
                  <td>{{ t.minBuyIn }}–{{ t.maxBuyIn }}</td>
                  <td>{{ t.seatedPlayers }}/{{ t.maxSeats }}</td>
                  <td>{{ t.rakeBasisPoints / 100 }}% (cap {{ t.rakeCap }})</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    </div>
  `,
  styles: [`
    .cash-admin { max-width: 960px; margin: 0 auto; padding: 1.5rem; color: #e2e8f0; }
    .hint { color: #94a3b8; }
    .card { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
            border-radius: 0.75rem; padding: 1.25rem; margin-top: 1rem; }
    .form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 0.75rem; }
    label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.85rem; color: #94a3b8; }
    input, select { padding: 0.4rem 0.6rem; border-radius: 0.4rem; border: 1px solid rgba(255,255,255,0.15);
                    background: #0f172a; color: #e2e8f0; }
    .btn-primary { margin-top: 1rem; padding: 0.6rem 1.2rem; border: none; border-radius: 0.5rem;
                   background: linear-gradient(135deg,#22c55e,#16a34a); color: #fff; font-weight: 600; cursor: pointer; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-ghost { padding: 0.4rem 0.8rem; border-radius: 0.4rem; border: 1px solid rgba(255,255,255,0.15);
                 background: transparent; color: #e2e8f0; cursor: pointer; }
    .list-head { display: flex; align-items: center; justify-content: space-between; }
    .empty { color: #94a3b8; }
    table.tables { width: 100%; border-collapse: collapse; margin-top: 0.5rem; }
    table.tables th, table.tables td { text-align: left; padding: 0.5rem; border-bottom: 1px solid rgba(255,255,255,0.06); }
    table.tables th { color: #94a3b8; font-weight: 600; }
  `]
})
export class AdminCashComponent implements OnInit {
  private readonly service = inject(AdminCashService);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly assets = ['USDT_TRC20', 'USDT_ERC20', 'BTC', 'ETH', 'LTC'];

  readonly form: CreateCashTableRequest = {
    name: '', asset: 'USDT_TRC20',
    smallBlind: 0.5, bigBlind: 1, minBuyIn: 20, maxBuyIn: 200,
    maxSeats: 6, rakeBasisPoints: 500, rakeCap: 3
  };

  readonly tables = signal<CashTable[]>([]);
  readonly busy = signal(false);

  ngOnInit(): void {
    this.refresh();
  }

  canCreate(): boolean {
    const f = this.form;
    return f.name.trim().length >= 3
      && f.smallBlind > 0 && f.bigBlind > f.smallBlind
      && f.minBuyIn > 0 && f.maxBuyIn >= f.minBuyIn
      && f.maxSeats >= 2 && f.maxSeats <= 10
      && f.rakeBasisPoints >= 0 && f.rakeBasisPoints <= 10000
      && f.rakeCap >= 0;
  }

  create(): void {
    if (!this.canCreate()) {
      return;
    }
    this.busy.set(true);
    this.service.create({ ...this.form, name: this.form.name.trim() }).subscribe({
      next: table => {
        this.errorHandler.addSuccess('Cash table created', table.name);
        this.form.name = '';
        this.busy.set(false);
        this.refresh();
      },
      error: () => {
        this.errorHandler.addError('Could not create the cash table', 'Check the values (blinds, buy-in range, rake).');
        this.busy.set(false);
      }
    });
  }

  refresh(): void {
    this.busy.set(true);
    this.service.list().subscribe({
      next: tables => {
        this.tables.set(tables);
        this.busy.set(false);
      },
      error: () => {
        this.errorHandler.addError('Could not load cash tables');
        this.busy.set(false);
      }
    });
  }
}
