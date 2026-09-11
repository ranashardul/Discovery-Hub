import { Routes } from '@angular/router';

/**
 * Every feature is lazily loaded, so the initial bundle carries the shell only.
 * Route titles double as the browser tab title.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard — DiscoveryHub',
    loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
  },
  {
    path: 'search',
    title: 'Search — DiscoveryHub',
    loadComponent: () => import('./features/search/search-page').then((m) => m.SearchPage),
  },
  {
    path: 'cases',
    title: 'Cases — DiscoveryHub',
    loadComponent: () => import('./features/cases/cases-page').then((m) => m.CasesPage),
  },
  {
    path: 'cases/:id',
    title: 'Case detail — DiscoveryHub',
    loadComponent: () => import('./features/cases/case-detail-page').then((m) => m.CaseDetailPage),
  },
  {
    path: 'holds',
    title: 'Legal holds — DiscoveryHub',
    loadComponent: () => import('./features/holds/holds-page').then((m) => m.HoldsPage),
  },
  {
    path: 'exports',
    title: 'Exports — DiscoveryHub',
    loadComponent: () => import('./features/exports/exports-page').then((m) => m.ExportsPage),
  },
  {
    path: 'retention',
    title: 'Retention — DiscoveryHub',
    loadComponent: () => import('./features/retention/retention-page').then((m) => m.RetentionPage),
  },
  {
    path: 'demo',
    title: 'Retention demo — DiscoveryHub',
    loadComponent: () =>
      import('./features/demo/retention-demo-page').then((m) => m.RetentionDemoPage),
  },
  {
    path: 'audit',
    title: 'Audit trail — DiscoveryHub',
    loadComponent: () => import('./features/audit/audit-page').then((m) => m.AuditPage),
  },
  {
    path: 'messages/:id',
    title: 'Message — DiscoveryHub',
    loadComponent: () =>
      import('./features/messages/message-detail-page').then((m) => m.MessageDetailPage),
  },
  { path: '**', redirectTo: 'dashboard' },
];
