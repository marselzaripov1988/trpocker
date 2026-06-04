import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdminWithdrawalService } from '../services/admin-withdrawal.service';
import { AdminWithdrawal, WithdrawalSigningRequest } from '../models/withdrawal.models';

@Component({
  selector: 'app-admin-withdrawal-list',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="admin-page" data-cy="admin-withdrawals">
      <header class="admin-header">
        <h1>💸 Withdrawal moderation</h1>
        <button class="btn-link" (click)="reload()">↻ Refresh</button>
      </header>

      @if (error()) { <div class="alert error" role="alert">{{ error() }}</div> }

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else {
        <section class="card">
          <h2>Pending / in-progress ({{ rows().length }})</h2>
          @if (rows().length === 0) {
            <p class="muted">Nothing awaiting moderation.</p>
          } @else {
            <table class="data-table">
              <thead>
                <tr><th>User</th><th>Asset</th><th>Amount</th><th>To</th><th>Status</th><th>Actions</th></tr>
              </thead>
              <tbody>
                @for (w of rows(); track w.id) {
                  <tr>
                    <td class="mono">{{ w.userId }}</td>
                    <td>{{ w.asset }} <span class="muted">({{ w.network }})</span></td>
                    <td>{{ w.amount }}</td>
                    <td class="mono">{{ w.toAddress }}</td>
                    <td><span class="badge status">{{ w.status }}</span></td>
                    <td>
                      @if (w.status === 'PENDING_APPROVAL') {
                        <button class="btn-approve" (click)="approve(w)" [disabled]="busy()">Approve</button>
                        <button class="btn-reject" (click)="rejectId.set(w.id)" [disabled]="busy()">Reject</button>
                      }
                      @if (w.status === 'APPROVED') {
                        <button class="btn-link" (click)="exportUnsigned(w)" [disabled]="busy()">Export</button>
                        <button class="btn-link" (click)="broadcastId.set(w.id)" [disabled]="busy()">Broadcast</button>
                      }
                      @if (w.status === 'BROADCAST' || w.status === 'CONFIRMED') {
                        <span class="mono muted">tx {{ w.txId }}</span>
                      }
                    </td>
                  </tr>

                  @if (rejectId() === w.id) {
                    <tr><td colspan="6">
                      <div class="inline-form">
                        <input type="text" placeholder="reason (optional)"
                               (input)="reason.set($any($event.target).value)" [value]="reason()" />
                        <button class="btn-reject" (click)="reject(w)" [disabled]="busy()">Confirm reject</button>
                        <button class="btn-link" (click)="rejectId.set(null)">Cancel</button>
                      </div>
                    </td></tr>
                  }
                  @if (broadcastId() === w.id) {
                    <tr><td colspan="6">
                      <div class="inline-form">
                        <input type="text" placeholder="broadcast tx id / hash"
                               (input)="txId.set($any($event.target).value)" [value]="txId()" />
                        <button class="btn-approve" (click)="broadcast(w)" [disabled]="busy() || !txId()">Record broadcast</button>
                        <button class="btn-link" (click)="broadcastId.set(null)">Cancel</button>
                      </div>
                    </td></tr>
                  }
                  @if (exported()?.withdrawalId === w.id) {
                    <tr><td colspan="6">
                      <pre class="intent">{{ exported() | json }}</pre>
                      <p class="muted">Sign this offline (OfflineWithdrawalSigner), broadcast via a node, then use “Broadcast”.</p>
                    </td></tr>
                  }
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
    .admin-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    .admin-header h1 { margin: 0; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .card h2 { margin: 0 0 1rem; font-size: 1.1rem; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    .data-table th, .data-table td { text-align: left; padding: 0.5rem 0.6rem; border-bottom: 1px solid #334155; }
    .data-table th { color: #94a3b8; }
    .mono { font-family: monospace; font-size: 0.8rem; word-break: break-all; }
    .badge.status { background: #334155; padding: 0.15rem 0.5rem; border-radius: 6px; font-size: 0.75rem; }
    .btn-approve { background: #15803d; color: #fff; border: none; padding: 0.4rem 0.8rem; border-radius: 8px; font-weight: 600; cursor: pointer; margin-right: 0.4rem; }
    .btn-reject { background: #b91c1c; color: #fff; border: none; padding: 0.4rem 0.8rem; border-radius: 8px; font-weight: 600; cursor: pointer; margin-right: 0.4rem; }
    .btn-link { background: none; border: none; color: #60a5fa; cursor: pointer; margin-right: 0.4rem; }
    .inline-form { display: flex; gap: 0.5rem; align-items: center; padding: 0.5rem 0; }
    .inline-form input { flex: 1; padding: 0.45rem; border-radius: 6px; border: 1px solid #334155; background: #0f172a; color: #e2e8f0; }
    .intent { background: #0f172a; padding: 0.75rem; border-radius: 8px; color: #cbd5e1; overflow:auto; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .muted { color: #94a3b8; }
  `]
})
export class AdminWithdrawalListComponent implements OnInit {
  private readonly service = inject(AdminWithdrawalService);

  readonly rows = signal<AdminWithdrawal[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  readonly rejectId = signal<string | null>(null);
  readonly reason = signal('');
  readonly broadcastId = signal<string | null>(null);
  readonly txId = signal('');
  readonly exported = signal<WithdrawalSigningRequest | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.listPending().subscribe({
      next: list => { this.rows.set(list); this.loading.set(false); },
      error: err => { this.error.set(this.msg(err, 'Failed to load withdrawals')); this.loading.set(false); }
    });
  }

  approve(w: AdminWithdrawal): void {
    this.run(this.service.approve(w.id));
  }

  reject(w: AdminWithdrawal): void {
    this.run(this.service.reject(w.id, this.reason()), () => { this.rejectId.set(null); this.reason.set(''); });
  }

  broadcast(w: AdminWithdrawal): void {
    this.run(this.service.recordBroadcast(w.id, this.txId()), () => { this.broadcastId.set(null); this.txId.set(''); });
  }

  exportUnsigned(w: AdminWithdrawal): void {
    this.busy.set(true);
    this.service.exportUnsigned(w.id).subscribe({
      next: intent => { this.exported.set(intent); this.busy.set(false); },
      error: err => { this.error.set(this.msg(err, 'Export failed')); this.busy.set(false); }
    });
  }

  private run(obs: ReturnType<AdminWithdrawalService['approve']>, after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    obs.subscribe({
      next: updated => { this.updateRow(updated); this.busy.set(false); after?.(); },
      error: err => { this.error.set(this.msg(err, 'Action failed')); this.busy.set(false); }
    });
  }

  private updateRow(updated: AdminWithdrawal): void {
    this.rows.update(list => list.map(r => r.id === updated.id ? updated : r));
  }

  private msg(err: unknown, fallback: string): string {
    return (err as { error?: { message?: string } })?.error?.message ?? fallback;
  }
}
