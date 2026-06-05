import { Routes } from '@angular/router';
import { adminGuard } from '../guards/auth.guard';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./admin-tournament-list/admin-tournament-list.component').then(
        m => m.AdminTournamentListComponent
      ),
    title: 'Admin — Tournaments'
  },
  {
    path: 'create',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./admin-tournament-create/admin-tournament-create.component').then(
        m => m.AdminTournamentCreateComponent
      ),
    title: 'Admin — Create Tournament'
  },
  {
    path: 'federations',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./admin-federation/admin-federation.component').then(
        m => m.AdminFederationComponent
      ),
    title: 'Admin — Federated Pyramids'
  },
  {
    path: ':id',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./admin-tournament-detail/admin-tournament-detail.component').then(
        m => m.AdminTournamentDetailComponent
      ),
    title: 'Admin — Tournament'
  }
];
