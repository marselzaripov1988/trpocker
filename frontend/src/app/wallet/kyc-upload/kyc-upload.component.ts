import { Component, OnInit, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpEventType } from '@angular/common/http';
import { KycService } from '../services/kyc.service';

@Component({
  selector: 'app-kyc-upload',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="kyc-page" [class.embedded]="embedded()" data-cy="kyc-upload">
      @if (!embedded()) {
        <header class="kyc-header">
          <h1>🪪 Identity verification (KYC)</h1>
          <p class="subtitle">Required before you can withdraw.</p>
        </header>
      }

      <section class="card">
        @if (embedded()) { <h2 class="card-title">🪪 Identity verification (KYC)</h2> }
        <p>
          Current status:
          <span class="badge status" [class.ok]="status() === 'VERIFIED'"
                [class.bad]="status() === 'REJECTED'">{{ status() ?? '—' }}</span>
        </p>

        @if (status() === 'VERIFIED') {
          <p class="muted">Your identity is verified. No further action needed.</p>
        } @else {
          <ol class="steps">
            <li>Record a short video of yourself <strong>holding your passport</strong> next to your face.</li>
            <li>Make sure the passport photo and text are readable.</li>
            <li>Upload the video below (max 50&nbsp;MB).</li>
          </ol>

          <div class="upload-row">
            <input type="file" accept="video/*" (change)="onFileSelected($event)"
                   data-cy="kyc-file-input" [disabled]="uploading()" />
            <button class="btn-primary" (click)="submit()"
                    [disabled]="!selectedFile() || uploading()" data-cy="kyc-submit">
              {{ uploading() ? 'Uploading…' : 'Submit for review' }}
            </button>
          </div>
          @if (selectedFile(); as f) {
            <p class="muted">Selected: {{ f.name }} ({{ (f.size / 1048576).toFixed(1) }} MB)</p>
          }
          @if (progress() !== null) {
            <div class="progress" role="progressbar" [attr.aria-valuenow]="progress()">
              <div class="progress-bar" [style.width.%]="progress()"></div>
              <span class="progress-label">{{ progress() }}%</span>
            </div>
          }
        }

        @if (success()) {
          <div class="alert ok" role="status">Uploaded — your verification is now pending review.</div>
        }
        @if (error()) {
          <div class="alert error" role="alert">{{ error() }}</div>
        }
      </section>
    </div>
  `,
  styles: [`
    .kyc-page { max-width: 720px; margin: 0 auto; padding: 1.5rem; }
    .kyc-page.embedded { max-width: none; margin: 0; padding: 0; }
    .card-title { margin: 0 0 0.75rem; font-size: 1.05rem; }
    .progress { position: relative; height: 18px; background: #0f172a; border-radius: 9px; overflow: hidden; margin: 0.5rem 0; }
    .progress-bar { height: 100%; background: #2563eb; transition: width 0.2s ease; }
    .progress-label { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; color: #e2e8f0; }
    .kyc-header h1 { margin: 0 0 0.25rem; }
    .subtitle { color: #94a3b8; margin: 0 0 1rem; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .steps { color: #cbd5e1; line-height: 1.6; }
    .upload-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; margin: 1rem 0 0.5rem; }
    .btn-primary { background: #2563eb; color: #fff; padding: 0.6rem 1rem; border-radius: 8px; border: none; font-weight: 600; cursor: pointer; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge.status { background: #334155; padding: 0.15rem 0.5rem; border-radius: 6px; font-size: 0.8rem; }
    .badge.status.ok { background: #0f766e; }
    .badge.status.bad { background: #7f1d1d; }
    .alert { padding: 0.75rem; border-radius: 8px; margin-top: 1rem; }
    .alert.ok { background: #064e3b; color: #bbf7d0; }
    .alert.error { background: #7f1d1d; color: #fecaca; }
    .muted { color: #94a3b8; }
  `]
})
export class KycUploadComponent implements OnInit {
  private readonly kyc = inject(KycService);

  /** When embedded (e.g. inside the wallet dashboard), drop the standalone page header/chrome. */
  readonly embedded = input(false);

  readonly status = signal<string | null>(null);
  readonly selectedFile = signal<File | null>(null);
  readonly uploading = signal(false);
  readonly progress = signal<number | null>(null);
  readonly success = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.refreshStatus();
  }

  refreshStatus(): void {
    this.kyc.getStatus().subscribe({
      next: r => this.status.set(r.status),
      error: () => { /* unauthenticated or payments off — leave status blank */ }
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files && input.files.length > 0 ? input.files[0] : null);
    this.success.set(false);
    this.error.set(null);
  }

  submit(): void {
    const file = this.selectedFile();
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.progress.set(0);
    this.success.set(false);
    this.error.set(null);
    this.kyc.uploadVerificationVideo(file).subscribe({
      next: event => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response) {
          this.status.set(event.body?.status ?? null);
          this.success.set(true);
          this.uploading.set(false);
          this.progress.set(null);
          this.selectedFile.set(null);
        }
      },
      error: err => {
        this.error.set(err?.error?.message ?? err?.error?.error ?? 'Upload failed');
        this.uploading.set(false);
        this.progress.set(null);
      }
    });
  }
}
