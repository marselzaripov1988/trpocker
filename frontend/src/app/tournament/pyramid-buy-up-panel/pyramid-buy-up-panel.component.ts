import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  input,
  output,
  signal
} from '@angular/core';
import { CommonModule } from '@angular/common';

import { ErrorHandlerService } from '../../services/error-handler.service';
import {
  PyramidTicket,
  TournamentPyramidService
} from '../../services/tournament-pyramid.service';

/**
 * Player-facing "tickets" panel for a buy-up pyramid: lists the buyable higher-level seats with their price
 * and lets the player buy one (charging the wallet at the seat price, which replaces the flat buy-in). Shown
 * only while the tournament is REGISTERING and buy-up is enabled — the parent gates rendering.
 */
@Component({
  selector: 'app-pyramid-buy-up-panel',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="buyup-panel" data-cy="pyramid-buyup-panel">
      <header class="buyup-header">
        <h3>🎟️ Buy a higher-level seat</h3>
        <p class="hint">
          Buying a seat skips (and closes) the whole sub-pyramid below it. The price replaces your buy-in.
        </p>
      </header>

      @if (loading()) {
        <div class="buyup-loading" data-cy="buyup-loading">
          <div class="spinner"></div><span>Loading tickets…</span>
        </div>
      } @else if (tickets().length === 0) {
        <p class="buyup-empty" data-cy="buyup-empty">No seats are currently available to buy.</p>
      } @else {
        <ul class="ticket-list">
          @for (t of tickets(); track ticketKey(t)) {
            <li class="ticket" data-cy="ticket">
              <div class="ticket-info">
                <span class="ticket-level">Level {{ t.level }}</span>
                <span class="ticket-seat">seat #{{ t.seatIndex }}</span>
              </div>
              <div class="ticket-buy">
                <span class="ticket-price" data-cy="ticket-price">{{ t.price | number }} {{ t.asset }}</span>
                <button
                  class="btn-buy"
                  data-cy="buy-seat-btn"
                  [disabled]="busyKey() !== null"
                  (click)="buy(t)">
                  @if (busyKey() === ticketKey(t)) { Buying… } @else { Buy }
                </button>
              </div>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: [`
    .buyup-panel {
      background: rgba(124, 58, 237, 0.08);
      border: 1px solid rgba(124, 58, 237, 0.35);
      border-radius: 12px;
      padding: 1rem 1.25rem;
      margin-top: 1rem;
    }
    .buyup-header h3 { margin: 0 0 0.25rem; color: #c4b5fd; }
    .hint { margin: 0 0 0.75rem; font-size: 0.85rem; color: #9ca3af; }
    .buyup-loading { display: flex; align-items: center; gap: 0.5rem; color: #9ca3af; }
    .spinner {
      width: 16px; height: 16px; border-radius: 50%;
      border: 2px solid rgba(196, 181, 253, 0.3); border-top-color: #c4b5fd;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    .buyup-empty { color: #9ca3af; font-style: italic; }
    .ticket-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.5rem; }
    .ticket {
      display: flex; justify-content: space-between; align-items: center;
      background: rgba(0, 0, 0, 0.25); border-radius: 8px; padding: 0.6rem 0.85rem;
    }
    .ticket-info { display: flex; flex-direction: column; }
    .ticket-level { font-weight: 600; color: #e5e7eb; }
    .ticket-seat { font-size: 0.8rem; color: #9ca3af; }
    .ticket-buy { display: flex; align-items: center; gap: 0.75rem; }
    .ticket-price { color: #fbbf24; font-weight: 600; }
    .btn-buy {
      background: linear-gradient(135deg, #7c3aed, #6d28d9); color: #fff; border: none;
      border-radius: 8px; padding: 0.45rem 1.1rem; font-weight: 600; cursor: pointer;
    }
    .btn-buy:disabled { opacity: 0.5; cursor: not-allowed; }
  `]
})
export class PyramidBuyUpPanelComponent implements OnInit {
  private readonly pyramidService = inject(TournamentPyramidService);
  private readonly errorHandler = inject(ErrorHandlerService);

  readonly tournamentId = input.required<string>();
  /** Emitted after a successful purchase so the parent can refresh the tournament. */
  readonly purchased = output<void>();

  readonly tickets = signal<readonly PyramidTicket[]>([]);
  readonly loading = signal(true);
  /** Key of the ticket whose purchase is in flight, or null. */
  readonly busyKey = signal<string | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  ticketKey(t: PyramidTicket): string {
    return `${t.level}:${t.seatIndex}`;
  }

  buy(ticket: PyramidTicket): void {
    if (this.busyKey() !== null) {
      return;
    }
    this.busyKey.set(this.ticketKey(ticket));
    this.pyramidService.buySeat(this.tournamentId(), ticket.level, ticket.seatIndex).subscribe({
      next: result => {
        this.errorHandler.addSuccess(
          `Seat bought: level ${result.level}, #${result.seatIndex}`,
          `Charged ${result.price} ${result.asset}`);
        this.busyKey.set(null);
        this.purchased.emit();
        this.reload();
      },
      error: () => {
        this.errorHandler.addError('Could not buy the seat', 'It may no longer be available.');
        this.busyKey.set(null);
        this.reload();
      }
    });
  }

  private reload(): void {
    this.loading.set(true);
    this.pyramidService.tickets(this.tournamentId()).subscribe({
      next: tickets => {
        this.tickets.set(tickets);
        this.loading.set(false);
      },
      error: () => {
        this.tickets.set([]);
        this.loading.set(false);
      }
    });
  }
}
