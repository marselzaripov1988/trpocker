import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdminPoolService } from '../services/admin-pool.service';
import { PoolAssetCount, PoolEntry, PoolImportResult } from '../models/pool.models';

/** Low-watermark: highlight assets whose free address count drops to/below this. */
const LOW_WATERMARK = 20;

/** Admin deposit-address pool: monitor depth (free/assigned per asset) and import an offline batch. */
@Component({
  selector: 'app-admin-pool',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="admin-page" data-cy="admin-pool">
      <header class="admin-header">
        <h1>🪙 Deposit-address pool</h1>
        <button class="btn-link" (click)="reload()">↻ Refresh</button>
      </header>

      @if (error()) { <div class="alert error" role="alert">{{ error() }}</div> }

      <section class="card">
        <h2>Depth per asset</h2>
        @if (loading()) {
          <p class="muted">Loading…</p>
        } @else if (assets().length === 0) {
          <p class="muted">Pool is empty — import a batch below.</p>
        } @else {
          <table class="data-table">
            <thead><tr><th>Asset</th><th>Free</th><th>Assigned</th></tr></thead>
            <tbody>
              @for (a of assets(); track a.asset) {
                <tr [class.low]="a.free <= lowWatermark">
                  <td>{{ a.asset }}</td>
                  <td>{{ a.free }} @if (a.free <= lowWatermark) { <span class="warn">⚠ refill</span> }</td>
                  <td>{{ a.assigned }}</td>
                </tr>
              }
            </tbody>
          </table>
          <p class="muted">Assets with ≤ {{ lowWatermark }} free addresses are flagged for refill.</p>
        }
      </section>

      <section class="card">
        <h2>Import offline-generated addresses</h2>
        <p class="muted">
          Paste the public <code>addresses.json</code> from the offline generator — either a JSON array of
          <code>{{ '{' }} asset, derivationIndex, address {{ '}' }}</code> or an object with an
          <code>addresses</code> field. No private keys.
        </p>
        <textarea rows="8" [value]="importJson()" (input)="importJson.set($any($event.target).value)"
                  data-cy="pool-import-json" placeholder='[{ "asset": "ETH", "derivationIndex": 0, "address": "0x…" }]'></textarea>
        <div class="row">
          <button class="btn-primary" (click)="doImport()" [disabled]="busy() || !importJson().trim()" data-cy="pool-import">
            {{ busy() ? 'Importing…' : 'Import batch' }}
          </button>
          @if (result(); as r) {
            <span class="result">Imported <strong>{{ r.imported }}</strong>, skipped {{ r.skipped }} (duplicates).</span>
          }
        </div>
        @if (importError()) { <div class="alert error">{{ importError() }}</div> }
      </section>
    </div>
  `,
  styles: [`
    .admin-page { max-width: 900px; margin: 0 auto; padding: 1.5rem; }
    .admin-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    .admin-header h1 { margin: 0; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; margin-bottom: 1rem; }
    .card h2 { margin: 0 0 0.75rem; font-size: 1.05rem; }
    .data-table { width: 100%; border-collapse: collapse; }
    .data-table th, .data-table td { text-align: left; padding: 0.5rem 0.6rem; border-bottom: 1px solid #334155; }
    .data-table th { color: #94a3b8; }
    tr.low td { background: #422006; }
    .warn { color: #fbbf24; margin-left: 0.4rem; font-size: 0.8rem; }
    textarea { width: 100%; box-sizing: border-box; background: #0f172a; color: #e2e8f0; border: 1px solid #334155; border-radius: 8px; padding: 0.6rem; font-family: monospace; font-size: 0.8rem; }
    .row { display: flex; gap: 1rem; align-items: center; margin-top: 0.75rem; }
    .btn-primary { background: #2563eb; color: #fff; padding: 0.6rem 1rem; border-radius: 8px; border: none; font-weight: 600; cursor: pointer; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-link { background: none; border: none; color: #60a5fa; cursor: pointer; }
    .result { color: #bbf7d0; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-top: 0.75rem; }
    .muted { color: #94a3b8; }
  `]
})
export class AdminPoolComponent implements OnInit {
  private readonly service = inject(AdminPoolService);

  readonly lowWatermark = LOW_WATERMARK;
  readonly assets = signal<PoolAssetCount[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly importJson = signal('');
  readonly busy = signal(false);
  readonly result = signal<PoolImportResult | null>(null);
  readonly importError = signal<string | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.status().subscribe({
      next: s => { this.assets.set(s.assets ?? []); this.loading.set(false); },
      error: err => { this.error.set(this.msg(err, 'Failed to load pool status')); this.loading.set(false); }
    });
  }

  doImport(): void {
    let entries: PoolEntry[];
    try {
      entries = this.parse(this.importJson());
    } catch (e) {
      this.importError.set((e as Error).message);
      return;
    }
    this.busy.set(true);
    this.importError.set(null);
    this.result.set(null);
    this.service.importBatch(entries).subscribe({
      next: r => { this.result.set(r); this.busy.set(false); this.importJson.set(''); this.reload(); },
      error: err => { this.importError.set(this.msg(err, 'Import failed')); this.busy.set(false); }
    });
  }

  /** Accept either a raw array or an object with an `addresses` field; tolerate `index`/`derivationIndex`. */
  private parse(text: string): PoolEntry[] {
    const parsed = JSON.parse(text);
    const list = Array.isArray(parsed) ? parsed : parsed?.addresses;
    if (!Array.isArray(list) || list.length === 0) {
      throw new Error('Expected a non-empty JSON array of { asset, derivationIndex, address }');
    }
    return list.map((it: Record<string, unknown>) => {
      const address = it['address'];
      const asset = it['asset'];
      if (typeof asset !== 'string' || typeof address !== 'string') {
        throw new Error('Each entry needs string "asset" and "address"');
      }
      const idx = it['derivationIndex'] ?? it['index'] ?? 0;
      return { asset, derivationIndex: Number(idx), address };
    });
  }

  private msg(err: unknown, fallback: string): string {
    return (err as { error?: { message?: string } })?.error?.message ?? fallback;
  }
}
