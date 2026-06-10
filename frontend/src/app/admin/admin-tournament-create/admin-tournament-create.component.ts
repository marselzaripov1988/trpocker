import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AdminTournamentService } from '../services/admin-tournament.service';
import {
  CreateTournamentAdminRequest,
  TOURNAMENT_TYPE_OPTIONS
} from '../models/admin-tournament.models';

@Component({
  selector: 'app-admin-tournament-create',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  template: `
    <div class="admin-page" data-cy="admin-tournament-create">
      <header class="admin-header">
        <a routerLink="/admin/tournaments" class="back">← Back</a>
        <h1>Create tournament</h1>
      </header>

      @if (error()) {
        <div class="alert error">{{ error() }}</div>
      }

      <form class="card form" (ngSubmit)="onSubmit()" #f="ngForm">
        <label>
          Name
          <input name="name" [(ngModel)]="form.name" required minlength="3" data-cy="tournament-name" />
        </label>

        <label>
          Type
          <select name="type" [(ngModel)]="form.type" required data-cy="tournament-type">
            @for (opt of typeOptions; track opt.value) {
              <option [value]="opt.value">{{ opt.label }}</option>
            }
          </select>
        </label>

        <div class="row">
          <label>
            Starting chips
            <input type="number" name="startingChips" [(ngModel)]="form.startingChips" min="100" required />
          </label>
          <label>
            Buy-in
            <input type="number" name="buyIn" [(ngModel)]="form.buyIn" min="0" required />
          </label>
        </div>

        <div class="row">
          <label>
            Min players
            <input type="number" name="minPlayers" [(ngModel)]="form.minPlayers" min="2" required />
          </label>
          <label>
            Max players
            <input type="number" name="maxPlayers" [(ngModel)]="form.maxPlayers" min="2" max="10000" required />
          </label>
        </div>

        <label>
          Blind structure
          <select name="blindStructureType" [(ngModel)]="form.blindStructureType">
            <option value="STANDARD">Standard</option>
            <option value="TURBO">Turbo</option>
            <option value="DEEP">Deep</option>
          </select>
        </label>

        <fieldset class="money">
          <legend>Real money (optional)</legend>
          <div class="row">
            <label>
              Crypto buy-in amount
              <input type="number" name="cryptoBuyInAmount" [(ngModel)]="form.cryptoBuyInAmount"
                min="0" step="0.000001" data-cy="crypto-buyin" />
            </label>
            <label>
              Asset
              <input name="cryptoBuyInAsset" [(ngModel)]="form.cryptoBuyInAsset"
                placeholder="USDT_TRC20" data-cy="crypto-asset" />
            </label>
          </div>
          <label>
            House fee % (0–20, on the crypto prize pool)
            <input type="number" name="feePercent" [(ngModel)]="feePercent"
              min="0" max="20" step="0.5" data-cy="tournament-fee" />
          </label>
          <p class="hint">Leave the amount blank for a play-money tournament. A fee only applies to a real-money pool.</p>
        </fieldset>

        <label class="check">
          <input
            type="checkbox"
            name="unregisterRequiresApproval"
            [(ngModel)]="form.unregisterRequiresApproval"
            data-cy="unregister-approval" />
          Require admin approval to cancel participation / refund buy-ins &amp; tickets
        </label>

        <div class="actions">
          <button type="submit" class="btn-primary" [disabled]="saving() || f.invalid" data-cy="submit-create">
            {{ saving() ? 'Creating…' : 'Create' }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [`
    .admin-page { max-width: 560px; margin: 0 auto; padding: 1.5rem; }
    .back { color: #60a5fa; text-decoration: none; display: inline-block; margin-bottom: 0.5rem; }
    .admin-header h1 { margin: 0; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
    .form label { display: block; margin-bottom: 1rem; color: #cbd5e1; font-size: 0.85rem; }
    .form input, .form select { display: block; width: 100%; margin-top: 0.35rem; padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #f8fafc; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
    .form label.check { display: flex; align-items: flex-start; gap: 0.5rem; }
    .form label.check input { width: auto; margin-top: 0.15rem; }
    .form fieldset.money { border: 1px solid #475569; border-radius: 8px; padding: 0.75rem 1rem 0.25rem; margin-bottom: 1rem; }
    .form fieldset.money legend { color: #94a3b8; font-size: 0.8rem; padding: 0 0.4rem; }
    .form .hint { color: #94a3b8; font-size: 0.75rem; margin: 0 0 0.75rem; }
    .btn-primary { background: #2563eb; color: #fff; border: none; padding: 0.65rem 1.25rem; border-radius: 8px; cursor: pointer; font-weight: 600; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .alert.error { background: #7f1d1d; color: #fecaca; padding: 0.75rem; border-radius: 8px; margin-bottom: 1rem; }
  `]
})
export class AdminTournamentCreateComponent {
  private readonly adminService = inject(AdminTournamentService);
  private readonly router = inject(Router);

  readonly typeOptions = TOURNAMENT_TYPE_OPTIONS;
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  form: CreateTournamentAdminRequest = {
    name: '',
    type: 'FREEZEOUT',
    startingChips: 1500,
    minPlayers: 2,
    maxPlayers: 9,
    buyIn: 100,
    blindStructureType: 'STANDARD',
    unregisterRequiresApproval: false,
    cryptoBuyInAmount: null,
    cryptoBuyInAsset: null
  };

  /** House fee as a %, converted to basis points on submit (10% → 1000 bps, capped at 20%). */
  feePercent = 0;

  onSubmit(): void {
    if (this.form.type === 'SIT_AND_GO') {
      this.form.maxPlayers = Math.min(this.form.maxPlayers, 9);
    }
    if (this.form.type === 'PYRAMID') {
      this.form.maxPlayers = Math.max(this.form.maxPlayers, 10);
    }

    // Normalise the real-money fields: a blank/zero amount is play-money (no asset, no fee).
    const amount = Number(this.form.cryptoBuyInAmount) || 0;
    const asset = (this.form.cryptoBuyInAsset || '').trim();
    const realMoney = amount > 0 && asset.length > 0;
    this.form.cryptoBuyInAmount = realMoney ? amount : null;
    this.form.cryptoBuyInAsset = realMoney ? asset : null;
    this.form.feeBasisPoints = realMoney ? Math.round((this.feePercent || 0) * 100) : 0;

    this.saving.set(true);
    this.error.set(null);
    this.adminService.createTournament(this.form).subscribe({
      next: detail => {
        this.saving.set(false);
        this.router.navigate(['/admin/tournaments', detail.id]);
      },
      error: err => {
        this.error.set(err?.error?.message ?? 'Create failed');
        this.saving.set(false);
      }
    });
  }
}
