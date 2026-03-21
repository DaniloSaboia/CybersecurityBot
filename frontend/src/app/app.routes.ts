import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', loadComponent: () => import('./components/dashboard/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'chat', loadComponent: () => import('./components/chat/chat.component').then(m => m.ChatComponent) },
  { path: 'quiz', loadComponent: () => import('./components/quiz/quiz.component').then(m => m.QuizComponent) },
  { path: 'playbooks', loadComponent: () => import('./components/playbooks/playbooks.component').then(m => m.PlaybooksComponent) },
  { path: 'analytics', loadComponent: () => import('./components/analytics/analytics.component').then(m => m.AnalyticsComponent) },
  { path: '**', redirectTo: 'dashboard' }
];
