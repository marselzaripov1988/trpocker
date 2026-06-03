import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { KycService } from '../services/kyc.service';

@Component({
  selector: 'app-kyc-upload',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="kyc-page" data-cy="kyc-upload">
      <header class="kyc-header">
        <h1>🪪 Identity verification (KYC)</h1>
        <p class="subtitle">Required before you can withdraw.</p>
      </header>

      <section class="card">
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

  readonly status = signal<string | null>(null);
  readonly selectedFile = signal<File | null>(null);
  readonly uploading = signal(false);
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
    this.success.set(false);
    this.error.set(null);
    this.kyc.uploadVerificationVideo(file).subscribe({
      next: r => {
        this.status.set(r.status);
        this.success.set(true);
        this.uploading.set(false);
        this.selectedFile.set(null);
      },
      error: err => {
        this.error.set(err?.error?.message ?? err?.error?.error ?? 'Upload failed');
        this.uploading.set(false);
      }
    });
  }
}
