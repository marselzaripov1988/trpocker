import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AdminTournamentService } from '../services/admin-tournament.service';
import {
  AdminTournamentType,
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
    blindStructureType: 'STANDARD'
  };

  onSubmit(): void {
    if (this.form.type === 'SIT_AND_GO') {
      this.form.maxPlayers = Math.min(this.form.maxPlayers, 9);
    }
    if (this.form.type === 'PYRAMID') {
      this.form.maxPlayers = Math.max(this.form.maxPlayers, 10);
    }

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
