import { Routes } from '@angular/router';
import { gameGuard, replayGuard } from './guards/game.guard';
import { adminGuard } from './guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./home/home.component').then((m) => m.HomeComponent),
    title: "TruHoldem - Texas Hold'em Poker",
  },

  {
    path: 'game',
    loadComponent: () =>
      import('./game-table/game-table.component').then(
        (m) => m.GameTableComponent
      ),
    canActivate: [gameGuard],
    title: 'Game Table - TruHoldem',
  },

  {
    path: 'lobby',
    loadComponent: () =>
      import('./lobby/lobby.component').then((m) => m.LobbyComponent),
    title: 'Game Lobby - TruHoldem',
  },

  {
    path: 'leaderboard',
    loadComponent: () =>
      import('./leaderboard/leaderboard.component').then(
        (m) => m.LeaderboardComponent
      ),
    title: 'Leaderboard - TruHoldem',
  },

  {
    path: 'tournaments',
    loadChildren: () =>
      import('./tournament/tournament.routes').then((m) => m.TOURNAMENT_ROUTES),
    title: 'Tournaments - TruHoldem',
  },
  {
    path: 'admin/tournaments',
    loadChildren: () =>
      import('./admin/admin.routes').then((m) => m.ADMIN_ROUTES),
    title: 'Admin - TruHoldem',
  },
  {
    path: 'tournament',
    redirectTo: 'tournaments',
    pathMatch: 'full',
  },

  {
    path: 'settings',
    loadComponent: () =>
      import('./settings/settings.component').then((m) => m.SettingsComponent),
    title: 'Settings - TruHoldem',
  },

  {
    path: 'history',
    loadChildren: () =>
      import('./history/history.routes').then((m) => m.HISTORY_ROUTES),
    title: 'Hand History - TruHoldem',
  },

  {
    path: 'replay/:handId',
    loadComponent: () =>
      import('./hand-replay/hand-replay.component').then(
        (m) => m.HandReplayComponent
      ),
    canActivate: [replayGuard],
    title: 'Hand Replay - TruHoldem',
  },

  {
    path: 'auth',
    loadChildren: () => import('./auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },

  {
    path: 'wallet',
    loadComponent: () =>
      import('./wallet/wallet-dashboard/wallet-dashboard.component').then(
        (m) => m.WalletDashboardComponent
      ),
    title: 'Wallet - TruHoldem',
  },

  {
    path: 'kyc',
    loadComponent: () =>
      import('./wallet/kyc-upload/kyc-upload.component').then(
        (m) => m.KycUploadComponent
      ),
    title: 'Identity verification - TruHoldem',
  },
  {
    path: 'admin/kyc',
    loadComponent: () =>
      import('./admin/admin-kyc-review/admin-kyc-review.component').then(
        (m) => m.AdminKycReviewComponent
      ),
    canActivate: [adminGuard],
    title: 'Admin — KYC review',
  },
  {
    path: 'admin/withdrawals',
    loadComponent: () =>
      import('./admin/admin-withdrawal-list/admin-withdrawal-list.component').then(
        (m) => m.AdminWithdrawalListComponent
      ),
    canActivate: [adminGuard],
    title: 'Admin — Withdrawals',
  },
  {
    path: 'admin/pool',
    loadComponent: () =>
      import('./admin/admin-pool/admin-pool.component').then(
        (m) => m.AdminPoolComponent
      ),
    canActivate: [adminGuard],
    title: 'Admin — Deposit pool',
  },

  {
    path: 'start',
    redirectTo: 'game',
    pathMatch: 'full',
  },
  {
    path: 'login',
    redirectTo: 'auth/login',
    pathMatch: 'full',
  },
  {
    path: 'register',
    redirectTo: 'auth/register',
    pathMatch: 'full',
  },
  {
    path: '**',
    loadComponent: () =>
      import('./not-found/not-found.component').then(
        (m) => m.NotFoundComponent
      ),
    title: '404 - Page Not Found',
  },
  {
    path: 'analysis',
    loadChildren: () =>
      import('./analysis/analysis.routes').then((m) => m.ANALYSIS_ROUTES),
  },
];
