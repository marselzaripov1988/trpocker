import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';

import { AdminFederationService } from '../services/admin-federation.service';
import { FederationDetail, WalletImportEntry } from '../models/admin-federation.models';
import { ErrorHandlerService } from '../../services/error-handler.service';

/**
 * Admin console for an isolated-custody federated pyramid: import the offline-generated dedicated wallets, drive
 * the on-chain ATA lifecycle (pre-create / close, offline-signed) and the deposit + admin-approved refund flows.
 *
 * The signing operations are deliberately key-free here: the panel only triggers the backend's build / broadcast
 * / confirm endpoints and shows the raw JSON. The operator copies an `unsigned` payload to the air-gapped signer,
 * signs it off-browser, and pastes the resulting base64 transaction back into the broadcast field. Private keys
 * never touch the UI.
 */
@Component({
  selector: 'app-isolated-custody-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="iso card" data-cy="iso-panel">
      <h2>🔐 Isolated custody</h2>
      <p class="hint">Each buy-in is paid on-chain into a dedicated per-player Solana wallet. Keys are generated
        and signed offline — this console only triggers the backend and shows the response.</p>

      <details open>
        <summary>1 · Import dedicated wallets</summary>
        <p class="hint">Paste a generator chunk file (<code>{{ '{' }} "wallets": [...] {{ '}' }}</code> or a bare
          array). Idempotent — re-importing skips ones already present.</p>
        <textarea data-cy="iso-import-json" rows="5" [(ngModel)]="walletsJson"
          placeholder="Paste the generator chunk file (a wallets array, or an object with a wallets array)."></textarea>
        <button class="btn" data-cy="iso-import" [disabled]="busy() || !walletsJson.trim()" (click)="importWallets()">
          Import wallets
        </button>
      </details>

      <details open>
        <summary>2 · Pre-create / close ATAs (offline-signed)</summary>
        <p class="hint">An exchange's bare transfer won't create the recipient ATA, so pre-create it before
          publishing the deposit address. Close empty ATAs afterwards to reclaim rent to the operator.</p>
        <div class="row">
          <label>Create-batch limit
            <input type="number" data-cy="iso-ata-limit" [(ngModel)]="ataLimit" min="1" placeholder="default" /></label>
          <button class="btn" data-cy="iso-ata-create-build" [disabled]="busy()" (click)="ataCreateUnsigned()">
            Build create batch
          </button>
        </div>
        <div class="row">
          <label>Close wallet ids (CSV)
            <input data-cy="iso-ata-close-ids" [(ngModel)]="ataCloseIds" placeholder="uuid, uuid, …" /></label>
          <button class="btn" data-cy="iso-ata-close-build" [disabled]="busy() || !hasIds(ataCloseIds)"
            (click)="ataCloseUnsigned()">Build close batch</button>
        </div>
        <p class="hint">Sign the <code>messageBase64</code> offline (operator + any owner signers, in order), then:</p>
        <textarea data-cy="iso-ata-signed" rows="3" [(ngModel)]="ataSignedTx"
          placeholder="signed base64 transaction"></textarea>
        <div class="row">
          <button class="btn" data-cy="iso-ata-broadcast" [disabled]="busy() || !ataSignedTx.trim()"
            (click)="ataBroadcast()">Broadcast</button>
        </div>
        <div class="row">
          <label>Signature
            <input data-cy="iso-ata-sig" [(ngModel)]="ataSignature" placeholder="tx signature" /></label>
          <label>Batch wallet ids (CSV)
            <input data-cy="iso-ata-confirm-ids" [(ngModel)]="ataConfirmIds" placeholder="uuid, uuid, …" /></label>
          <button class="btn" data-cy="iso-ata-confirm-create"
            [disabled]="busy() || !ataSignature.trim() || !hasIds(ataConfirmIds)"
            (click)="ataConfirm(true)">Confirm created</button>
          <button class="btn" data-cy="iso-ata-confirm-close"
            [disabled]="busy() || !ataSignature.trim() || !hasIds(ataConfirmIds)"
            (click)="ataConfirm(false)">Confirm closed</button>
        </div>
      </details>

      <details open>
        <summary>3 · Deposits</summary>
        <div class="row">
          <button class="btn" data-cy="iso-reconcile" [disabled]="busy()" (click)="reconcileDeposits()">
            Reconcile deposits (seat funded)
          </button>
          <button class="btn-ghost" data-cy="iso-release" [disabled]="busy()" (click)="releaseNoShows()">
            Release no-shows
          </button>
        </div>
      </details>

      <details>
        <summary>4 · Refunds (admin-approved)</summary>
        <p class="hint">Return a funded buy-in. Net = funded − fee; the player's destination is set at approval.</p>
        <div class="row">
          <label>Player id
            <input data-cy="iso-refund-player" [(ngModel)]="refundPlayerId" placeholder="uuid" /></label>
          <button class="btn" data-cy="iso-refund-request"
            [disabled]="busy() || !refundPlayerId.trim()" (click)="requestRefund()">Request refund</button>
          <button class="btn-ghost" data-cy="iso-refund-request-all" [disabled]="busy()"
            (click)="requestRefundsForCancelled()">Request all (cancelled)</button>
        </div>
        <div class="row">
          <label>Refund id
            <input data-cy="iso-refund-id" [(ngModel)]="refundId" placeholder="uuid" /></label>
          <label>Destination address
            <input data-cy="iso-refund-addr" [(ngModel)]="refundToAddress" placeholder="player Solana address" /></label>
        </div>
        <div class="row">
          <button class="btn" data-cy="iso-refund-approve"
            [disabled]="busy() || !refundId.trim() || !refundToAddress.trim()" (click)="approveRefund()">Approve</button>
          <button class="btn-ghost" data-cy="iso-refund-reject" [disabled]="busy() || !refundId.trim()"
            (click)="rejectRefund()">Reject</button>
          <button class="btn" data-cy="iso-refund-unsigned" [disabled]="busy() || !refundId.trim()"
            (click)="refundUnsigned()">Build unsigned</button>
          <button class="btn" data-cy="iso-refund-reconcile" [disabled]="busy() || !refundId.trim()"
            (click)="refundReconcile()">Reconcile</button>
        </div>
        <textarea data-cy="iso-refund-signed" rows="3" [(ngModel)]="refundSignedTx"
          placeholder="signed base64 refund transaction"></textarea>
        <div class="row">
          <button class="btn" data-cy="iso-refund-broadcast"
            [disabled]="busy() || !refundId.trim() || !refundSignedTx.trim()" (click)="refundBroadcast()">
            Broadcast refund
          </button>
        </div>
      </details>

      @if (opLabel(); as label) {
        <div class="result">
          <span class="hint">Last: <strong>{{ label }}</strong></span>
          <pre data-cy="iso-op-result">{{ opResult() }}</pre>
        </div>
      }
    </section>
  `,
  styles: [`
    .iso { margin-top: 1rem; }
    .iso h2 { margin-top: 0; }
    .hint { color: #9ca3af; font-size: 0.85rem; }
    details { border-top: 1px solid rgba(255,255,255,0.1); padding: 0.6rem 0; }
    summary { cursor: pointer; font-weight: 600; color: #c7d2fe; }
    .row { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: flex-end; margin: 0.5rem 0; }
    label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8rem; color: #cbd5e1; }
    input { padding: 0.45rem; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15); background: rgba(0,0,0,0.25); color: #fff; min-width: 180px; }
    textarea { width: 100%; margin: 0.4rem 0; padding: 0.5rem; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15); background: rgba(0,0,0,0.25); color: #fff; font-family: monospace; font-size: 0.8rem; }
    .btn { background: linear-gradient(135deg,#6366f1,#4f46e5); color:#fff; border:none; border-radius:8px; padding:0.45rem 0.9rem; font-weight:600; cursor:pointer; }
    .btn-ghost { background: transparent; color:#cbd5e1; border:1px solid rgba(255,255,255,0.2); border-radius:8px; padding:0.45rem 0.9rem; cursor:pointer; }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    .result { margin-top: 0.75rem; }
    pre { background: rgba(0,0,0,0.35); border-radius: 8px; padding: 0.6rem; overflow: auto; max-height: 240px; font-size: 0.78rem; color: #e5e7eb; }
  `]
})
export class IsolatedCustodyPanelComponent {
  private readonly service = inject(AdminFederationService);
  private readonly errorHandler = inject(ErrorHandlerService);

  @Input({ required: true }) federation!: FederationDetail;
  /** Ask the parent to reload the federation after an op that changes seating. */
  @Output() readonly refreshRequested = new EventEmitter<void>();

  readonly busy = signal(false);
  readonly opResult = signal('');
  readonly opLabel = signal('');

  walletsJson = '';
  ataLimit: number | null = null;
  ataCloseIds = '';
  ataSignedTx = '';
  ataSignature = '';
  ataConfirmIds = '';
  refundPlayerId = '';
  refundId = '';
  refundToAddress = '';
  refundSignedTx = '';

  hasIds(csv: string): boolean {
    return this.parseIds(csv).length > 0;
  }

  importWallets(): void {
    let entries: unknown;
    try {
      const parsed = JSON.parse(this.walletsJson);
      entries = Array.isArray(parsed) ? parsed : parsed?.wallets;
    } catch {
      this.errorHandler.addError('Invalid JSON', 'Paste a chunk file or a bare wallet array.');
      return;
    }
    if (!Array.isArray(entries) || entries.length === 0) {
      this.errorHandler.addError('No wallets found', 'Expected { "wallets": [...] } or a non-empty array.');
      return;
    }
    this.run('import wallets', this.service.importWallets(this.federation.id, entries as WalletImportEntry[]), true);
  }

  reconcileDeposits(): void {
    this.run('reconcile deposits', this.service.reconcileDeposits(this.federation.id), true);
  }

  releaseNoShows(): void {
    this.run('release no-shows', this.service.releaseNoShows(this.federation.id), true);
  }

  ataCreateUnsigned(): void {
    this.run('ATA create — unsigned', this.service.ataCreateUnsigned(this.federation.id, this.ataLimit ?? undefined));
  }

  ataCloseUnsigned(): void {
    this.run('ATA close — unsigned', this.service.ataCloseUnsigned(this.federation.id, this.parseIds(this.ataCloseIds)));
  }

  ataBroadcast(): void {
    this.run('ATA broadcast', this.service.ataBroadcast(this.federation.id, this.ataSignedTx.trim()));
  }

  ataConfirm(created: boolean): void {
    const sig = this.ataSignature.trim();
    const ids = this.parseIds(this.ataConfirmIds);
    this.run(created ? 'ATA confirm created' : 'ATA confirm closed',
      created ? this.service.ataConfirmCreated(this.federation.id, sig, ids)
              : this.service.ataConfirmClosed(this.federation.id, sig, ids), created);
  }

  requestRefund(): void {
    this.run('request refund', this.service.requestRefund(this.federation.id, this.refundPlayerId.trim()));
  }

  requestRefundsForCancelled(): void {
    this.run('request refunds (cancelled)', this.service.requestRefundsForCancelled(this.federation.id));
  }

  approveRefund(): void {
    this.run('approve refund', this.service.approveRefund(this.refundId.trim(), this.refundToAddress.trim()));
  }

  rejectRefund(): void {
    this.run('reject refund', this.service.rejectRefund(this.refundId.trim(), 'Rejected by admin'));
  }

  refundUnsigned(): void {
    this.run('refund — unsigned', this.service.refundUnsigned(this.refundId.trim()));
  }

  refundBroadcast(): void {
    this.run('refund broadcast', this.service.refundBroadcast(this.refundId.trim(), this.refundSignedTx.trim()));
  }

  refundReconcile(): void {
    this.run('refund reconcile', this.service.refundReconcile(this.refundId.trim()));
  }

  /** Run an op: show the response JSON, toast, optionally ask the parent to reload the federation. */
  private run(label: string, obs: Observable<unknown>, refresh = false): void {
    this.busy.set(true);
    obs.subscribe({
      next: res => {
        this.opLabel.set(label);
        this.opResult.set(JSON.stringify(res, null, 2));
        this.errorHandler.addSuccess(`Done: ${label}`, '');
        this.busy.set(false);
        if (refresh) {
          this.refreshRequested.emit();
        }
      },
      error: () => {
        this.errorHandler.addError(`Failed: ${label}`, 'Check the inputs, the federation state and the feature flag.');
        this.busy.set(false);
      }
    });
  }

  private parseIds(csv: string): string[] {
    return (csv || '').split(/[\s,]+/).map(s => s.trim()).filter(Boolean);
  }
}
