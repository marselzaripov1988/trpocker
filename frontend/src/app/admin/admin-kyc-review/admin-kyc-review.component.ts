import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { AdminKycService, KycReEncryptResult } from '../services/admin-kyc.service';
import { KycDecision, KycPendingItem } from '../models/kyc.models';

@Component({
  selector: 'app-admin-kyc-review',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="admin-page" data-cy="admin-kyc-review">
      <header class="admin-header">
        <h1>🛡️ KYC review</h1>
        <div class="header-actions">
          <button class="btn-link" (click)="reEncrypt()" [disabled]="reEncrypting()"
                  title="Re-encrypt all KYC media under the active key (rotation / KMS migration)"
                  data-cy="kyc-reencrypt">
            {{ reEncrypting() ? 'Re-encrypting…' : '🔁 Re-encrypt keys' }}
          </button>
          <button class="btn-link" (click)="reload()">↻ Refresh</button>
        </div>
      </header>

      @if (reEncryptResult(); as r) {
        <div class="alert ok" role="status">
          Re-encrypted {{ r.reEncrypted }}, skipped {{ r.skipped }} of {{ r.total }} document(s).
        </div>
      }
      @if (error()) {
        <div class="alert error" role="alert">{{ error() }}</div>
      }

      <div class="layout">
        <section class="card list">
          <h2>Pending ({{ pending().length }})</h2>
          @if (loading()) {
            <p class="muted">Loading…</p>
          } @else if (pending().length === 0) {
            <p class="muted">Nothing awaiting review.</p>
          } @else {
            <table class="data-table">
              <thead>
                <tr><th>User</th><th>File</th><th>Size</th><th>Uploaded</th><th></th></tr>
              </thead>
              <tbody>
                @for (item of pending(); track item.userId) {
                  <tr [class.selected]="selected()?.userId === item.userId">
                    <td class="mono">{{ item.userId }}</td>
                    <td>{{ item.originalFilename ?? '—' }}</td>
                    <td>{{ (item.sizeBytes / 1048576).toFixed(1) }} MB</td>
                    <td>{{ item.uploadedAt | date:'short' }}</td>
                    <td><button class="btn-link" (click)="review(item)">Review →</button></td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>

        @if (selected(); as sel) {
          <section class="card review">
            <h2>Review</h2>
            <p class="mono muted">{{ sel.userId }}</p>
            @if (videoUrl(); as url) {
              <video [src]="url" controls class="video"></video>
            } @else {
              <p class="muted">Loading video…</p>
            }
            <label class="note-label">Note (optional)
              <input type="text" class="note" (input)="note.set($any($event.target).value)"
                     [value]="note()" placeholder="reason / reference" />
            </label>
            <div class="actions">
              <button class="btn-approve" (click)="decide('VERIFIED')" [disabled]="busy()">Approve</button>
              <button class="btn-reject" (click)="decide('REJECTED')" [disabled]="busy()">Reject</button>
              <button class="btn-link danger" (click)="erase()" [disabled]="busy()">Erase (GDPR)</button>
            </div>
          </section>
        }
      </div>
    </div>
  `,
  styles: [`
    .admin-page { max-width: 1100px; margin: 0 auto; padding: 1.5rem; }
    .admin-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    .admin-header h1 { margin: 0; }
    .header-actions { display: flex; gap: 1rem; align-items: center; }
    .alert.ok { background: #064e3b; color: #bbf7d0; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .layout { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: start; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .card h2 { margin: 0 0 1rem; font-size: 1.1rem; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    .data-table th, .data-table td { text-align: left; padding: 0.5rem 0.6rem; border-bottom: 1px solid #334155; }
    .data-table th { color: #94a3b8; }
    tr.selected { background: #0f3460; }
    .mono { font-family: monospace; font-size: 0.8rem; }
    .video { width: 100%; border-radius: 8px; background: #000; margin-bottom: 0.75rem; }
    .note-label { display: block; color: #94a3b8; font-size: 0.85rem; margin-bottom: 0.75rem; }
    .note { width: 100%; padding: 0.5rem; border-radius: 6px; border: 1px solid #334155; background: #0f172a; color: #e2e8f0; margin-top: 0.25rem; }
    .actions { display: flex; gap: 0.75rem; align-items: center; }
    .btn-approve { background: #15803d; color: #fff; border: none; padding: 0.55rem 1rem; border-radius: 8px; font-weight: 600; cursor: pointer; }
    .btn-reject { background: #b91c1c; color: #fff; border: none; padding: 0.55rem 1rem; border-radius: 8px; font-weight: 600; cursor: pointer; }
    .btn-link { background: none; border: none; color: #60a5fa; cursor: pointer; }
    .btn-link.danger { color: #fca5a5; margin-left: auto; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
    .muted { color: #94a3b8; }
    @media (max-width: 800px) { .layout { grid-template-columns: 1fr; } }
  `]
})
export class AdminKycReviewComponent implements OnInit, OnDestroy {
  private readonly adminKyc = inject(AdminKycService);
  private readonly sanitizer = inject(DomSanitizer);

  readonly pending = signal<KycPendingItem[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly selected = signal<KycPendingItem | null>(null);
  readonly videoUrl = signal<SafeUrl | null>(null);
  readonly note = signal('');
  readonly busy = signal(false);
  readonly reEncrypting = signal(false);
  readonly reEncryptResult = signal<KycReEncryptResult | null>(null);

  private objectUrl: string | null = null;

  ngOnInit(): void {
    this.reload();
  }

  ngOnDestroy(): void {
    this.revokeVideo();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.adminKyc.listPending().subscribe({
      next: list => { this.pending.set(list); this.loading.set(false); },
      error: err => { this.error.set(err?.error?.message ?? 'Failed to load pending KYC'); this.loading.set(false); }
    });
  }

  review(item: KycPendingItem): void {
    this.selected.set(item);
    this.note.set('');
    this.revokeVideo();
    this.videoUrl.set(null);
    this.adminKyc.loadVideo(item.userId).subscribe({
      next: blob => {
        this.objectUrl = URL.createObjectURL(blob);
        this.videoUrl.set(this.sanitizer.bypassSecurityTrustUrl(this.objectUrl));
      },
      error: err => this.error.set(err?.error?.message ?? 'Failed to load video')
    });
  }

  decide(status: KycDecision): void {
    const sel = this.selected();
    if (!sel) {
      return;
    }
    this.busy.set(true);
    this.adminKyc.decide(sel.userId, status, this.note() || undefined).subscribe({
      next: () => { this.busy.set(false); this.clearSelection(); this.reload(); },
      error: err => { this.error.set(err?.error?.message ?? 'Decision failed'); this.busy.set(false); }
    });
  }

  erase(): void {
    const sel = this.selected();
    if (!sel) {
      return;
    }
    this.busy.set(true);
    this.adminKyc.erase(sel.userId).subscribe({
      next: () => { this.busy.set(false); this.clearSelection(); this.reload(); },
      error: err => { this.error.set(err?.error?.message ?? 'Erase failed'); this.busy.set(false); }
    });
  }

  reEncrypt(): void {
    if (!confirm('Re-encrypt ALL KYC media under the active key/provider? This rewrites every stored file.')) {
      return;
    }
    this.reEncrypting.set(true);
    this.error.set(null);
    this.reEncryptResult.set(null);
    this.adminKyc.reEncrypt().subscribe({
      next: r => { this.reEncryptResult.set(r); this.reEncrypting.set(false); },
      error: err => { this.error.set(err?.error?.message ?? 'Re-encryption failed'); this.reEncrypting.set(false); }
    });
  }

  private clearSelection(): void {
    this.selected.set(null);
    this.revokeVideo();
    this.videoUrl.set(null);
  }

  private revokeVideo(): void {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }
}
