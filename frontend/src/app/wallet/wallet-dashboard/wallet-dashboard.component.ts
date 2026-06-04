import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import * as QRCode from 'qrcode';
import {
  WalletService, WalletBalance, DepositAddress, Withdrawal, CreateWithdrawal
} from '../services/wallet.service';

/** Supported assets (CryptoAsset enum names) the player can deposit/withdraw. */
const ASSETS = ['ETH', 'BTC', 'USDT_ERC20', 'USDT_TRC20', 'LTC'];

/** Player wallet dashboard: balances, a deposit address per asset, and a withdrawal form + history. */
@Component({
  selector: 'app-wallet-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="wallet-page" data-cy="wallet-dashboard">
      <header class="wallet-header">
        <h1>💰 Wallet</h1>
        <p class="subtitle">
          Deposit crypto, withdraw to your own address.
          Withdrawals require <a routerLink="/kyc">identity verification</a>.
        </p>
      </header>

      <!-- Balances -->
      <section class="card">
        <div class="card-head">
          <h2>Balances</h2>
          <button class="btn-ghost" (click)="loadBalances()" [disabled]="loadingBalances()" data-cy="refresh-balances">
            {{ loadingBalances() ? '…' : '↻ Refresh' }}
          </button>
        </div>
        @if (balances().length === 0) {
          <p class="muted">No balances yet. Deposit to get started.</p>
        } @else {
          <ul class="balance-list">
            @for (b of balances(); track b.asset + b.network) {
              <li>
                <span class="asset">{{ b.asset }} <small class="net">{{ b.network }}</small></span>
                <span class="amount">{{ b.balance }}</span>
              </li>
            }
          </ul>
        }
      </section>

      <!-- Deposit -->
      <section class="card">
        <h2>Deposit</h2>
        <div class="row">
          <select [(ngModel)]="depositAsset" data-cy="deposit-asset">
            @for (a of assets; track a) { <option [value]="a">{{ a }}</option> }
          </select>
          <button class="btn-primary" (click)="getDepositAddress()" [disabled]="loadingDeposit()" data-cy="get-deposit">
            {{ loadingDeposit() ? 'Loading…' : 'Show deposit address' }}
          </button>
        </div>
        @if (deposit(); as d) {
          <div class="address-box" data-cy="deposit-address">
            <p class="muted">Send only <strong>{{ d.asset }}</strong> ({{ d.network }}) to this address:</p>
            @if (qrDataUrl(); as qr) {
              <img class="qr" [src]="qr" alt="Deposit address QR code" width="160" height="160" />
            }
            <div class="addr-row">
              <code class="addr">{{ d.address }}</code>
              <button class="btn-ghost" (click)="copy(d.address)">{{ copied() ? 'Copied ✓' : 'Copy' }}</button>
            </div>
          </div>
        }
        @if (depositError()) { <div class="alert error">{{ depositError() }}</div> }
      </section>

      <!-- Withdraw -->
      <section class="card">
        <h2>Withdraw</h2>
        <div class="form-grid">
          <label>Asset
            <select [(ngModel)]="form.asset" data-cy="withdraw-asset">
              @for (a of assets; track a) { <option [value]="a">{{ a }}</option> }
            </select>
          </label>
          <label>Destination address
            <input type="text" [(ngModel)]="form.toAddress" placeholder="0x… / bc1…" data-cy="withdraw-address" />
          </label>
          <label>Amount
            <input type="text" [(ngModel)]="form.amount" placeholder="0.00" data-cy="withdraw-amount" />
          </label>
        </div>
        <button class="btn-primary" (click)="submitWithdrawal()"
                [disabled]="submitting() || !form.toAddress || !form.amount" data-cy="withdraw-submit">
          {{ submitting() ? 'Submitting…' : 'Request withdrawal' }}
        </button>
        @if (withdrawSuccess()) {
          <div class="alert ok" role="status">Withdrawal requested — track its status below.</div>
        }
        @if (withdrawError()) { <div class="alert error" role="alert">{{ withdrawError() }}</div> }
      </section>

      <!-- History -->
      <section class="card">
        <div class="card-head">
          <h2>Withdrawal history</h2>
          <button class="btn-ghost" (click)="loadWithdrawals()" [disabled]="loadingWithdrawals()">
            {{ loadingWithdrawals() ? '…' : '↻ Refresh' }}
          </button>
        </div>
        @if (withdrawals().length === 0) {
          <p class="muted">No withdrawals yet.</p>
        } @else {
          <table class="history">
            <thead><tr><th>Asset</th><th>Amount</th><th>To</th><th>Status</th><th>Tx</th></tr></thead>
            <tbody>
              @for (w of withdrawals(); track w.id) {
                <tr>
                  <td>{{ w.asset }} <small class="net">{{ w.network }}</small></td>
                  <td>{{ w.amount }}</td>
                  <td class="mono">{{ shorten(w.toAddress) }}</td>
                  <td><span class="badge" [class]="'st-' + w.status">{{ w.status }}</span></td>
                  <td class="mono">{{ w.txId ? shorten(w.txId) : '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    </div>
  `,
  styles: [`
    .wallet-page { max-width: 820px; margin: 0 auto; padding: 1.5rem; }
    .wallet-header h1 { margin: 0 0 0.25rem; }
    .subtitle { color: #94a3b8; margin: 0 0 1rem; }
    .subtitle a { color: #60a5fa; }
    .card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; margin-bottom: 1rem; }
    .card-head { display: flex; justify-content: space-between; align-items: center; }
    .card h2 { margin: 0 0 0.75rem; font-size: 1.05rem; }
    .row, .form-grid { display: flex; gap: 0.75rem; flex-wrap: wrap; align-items: center; margin-bottom: 0.75rem; }
    .form-grid { flex-direction: column; align-items: stretch; }
    label { display: flex; flex-direction: column; gap: 0.25rem; color: #cbd5e1; font-size: 0.9rem; }
    select, input { background: #0f172a; color: #e2e8f0; border: 1px solid #334155; border-radius: 8px; padding: 0.5rem; }
    .btn-primary { background: #2563eb; color: #fff; padding: 0.6rem 1rem; border-radius: 8px; border: none; font-weight: 600; cursor: pointer; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-ghost { background: transparent; color: #93c5fd; border: 1px solid #334155; border-radius: 8px; padding: 0.4rem 0.7rem; cursor: pointer; }
    .balance-list { list-style: none; padding: 0; margin: 0; }
    .balance-list li { display: flex; justify-content: space-between; padding: 0.5rem 0; border-bottom: 1px solid #293548; }
    .asset { font-weight: 600; } .net { color: #94a3b8; font-weight: 400; }
    .amount { font-variant-numeric: tabular-nums; }
    .address-box { margin-top: 0.75rem; display: flex; flex-direction: column; gap: 0.5rem; }
    .addr-row { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .qr { background: #fff; padding: 6px; border-radius: 8px; align-self: flex-start; }
    .addr { background: #0f172a; padding: 0.4rem 0.6rem; border-radius: 6px; word-break: break-all; }
    .history { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .history th, .history td { text-align: left; padding: 0.5rem 0.4rem; border-bottom: 1px solid #293548; }
    .mono { font-family: monospace; color: #cbd5e1; }
    .badge { padding: 0.12rem 0.45rem; border-radius: 6px; font-size: 0.75rem; background: #334155; }
    .st-CONFIRMED { background: #0f766e; } .st-BROADCAST { background: #1d4ed8; }
    .st-PENDING_APPROVAL, .st-APPROVED { background: #92400e; }
    .st-FAILED, .st-REJECTED { background: #7f1d1d; }
    .alert { padding: 0.75rem; border-radius: 8px; margin-top: 0.75rem; }
    .alert.ok { background: #064e3b; color: #bbf7d0; } .alert.error { background: #7f1d1d; color: #fecaca; }
    .muted { color: #94a3b8; }
  `]
})
export class WalletDashboardComponent implements OnInit {
  private readonly wallet = inject(WalletService);

  readonly assets = ASSETS;
  depositAsset = 'ETH';
  form: CreateWithdrawal = { asset: 'ETH', toAddress: '', amount: '' };

  readonly balances = signal<WalletBalance[]>([]);
  readonly withdrawals = signal<Withdrawal[]>([]);
  readonly deposit = signal<DepositAddress | null>(null);
  readonly qrDataUrl = signal<string | null>(null);
  readonly loadingBalances = signal(false);
  readonly loadingWithdrawals = signal(false);
  readonly loadingDeposit = signal(false);
  readonly submitting = signal(false);
  readonly copied = signal(false);
  readonly depositError = signal<string | null>(null);
  readonly withdrawError = signal<string | null>(null);
  readonly withdrawSuccess = signal(false);

  ngOnInit(): void {
    this.loadBalances();
    this.loadWithdrawals();
  }

  loadBalances(): void {
    this.loadingBalances.set(true);
    this.wallet.balances().subscribe({
      next: b => { this.balances.set(b); this.loadingBalances.set(false); },
      error: () => this.loadingBalances.set(false)
    });
  }

  loadWithdrawals(): void {
    this.loadingWithdrawals.set(true);
    this.wallet.withdrawals().subscribe({
      next: w => { this.withdrawals.set(w); this.loadingWithdrawals.set(false); },
      error: () => this.loadingWithdrawals.set(false)
    });
  }

  getDepositAddress(): void {
    this.loadingDeposit.set(true);
    this.deposit.set(null);
    this.qrDataUrl.set(null);
    this.depositError.set(null);
    this.copied.set(false);
    this.wallet.depositAddress(this.depositAsset).subscribe({
      next: d => {
        this.deposit.set(d);
        this.loadingDeposit.set(false);
        QRCode.toDataURL(d.address, { margin: 1, width: 160 })
          .then(url => this.qrDataUrl.set(url))
          .catch(() => this.qrDataUrl.set(null));
      },
      error: err => { this.depositError.set(this.msg(err)); this.loadingDeposit.set(false); }
    });
  }

  submitWithdrawal(): void {
    this.submitting.set(true);
    this.withdrawError.set(null);
    this.withdrawSuccess.set(false);
    this.wallet.requestWithdrawal({ ...this.form }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.withdrawSuccess.set(true);
        this.form = { asset: this.form.asset, toAddress: '', amount: '' };
        this.loadWithdrawals();
        this.loadBalances();
      },
      error: err => { this.withdrawError.set(this.msg(err)); this.submitting.set(false); }
    });
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }

  shorten(s: string): string {
    return s.length > 16 ? `${s.slice(0, 8)}…${s.slice(-6)}` : s;
  }

  private msg(err: unknown): string {
    const e = err as { error?: { message?: string; error?: string } };
    return e?.error?.message ?? e?.error?.error ?? 'Request failed';
  }
}
