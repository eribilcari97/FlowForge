import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register').then((m) => m.Register),
  },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'projects',
        loadComponent: () => import('./features/projects/project-list').then((m) => m.ProjectList),
      },
      {
        path: 'projects/new',
        loadComponent: () => import('./features/projects/project-form').then((m) => m.ProjectForm),
      },
      {
        path: 'projects/:id',
        loadComponent: () =>
          import('./features/projects/project-detail').then((m) => m.ProjectDetail),
      },
      {
        path: 'projects/:id/edit',
        loadComponent: () => import('./features/projects/project-form').then((m) => m.ProjectForm),
      },
      {
        path: 'projects/:projectId/workflows/new',
        loadComponent: () =>
          import('./features/workflows/workflow-form').then((m) => m.WorkflowForm),
      },
      {
        path: 'workflows/:id',
        loadComponent: () =>
          import('./features/workflows/workflow-page').then((m) => m.WorkflowPage),
      },
      {
        path: 'workflows/:id/edit',
        loadComponent: () =>
          import('./features/workflows/workflow-form').then((m) => m.WorkflowForm),
      },
      {
        path: 'workflows/:id/steps/new',
        loadComponent: () => import('./features/workflows/step-form').then((m) => m.StepForm),
      },
      {
        path: 'workflows/:id/steps/:stepId/edit',
        loadComponent: () => import('./features/workflows/step-form').then((m) => m.StepForm),
      },
      {
        path: 'executions',
        loadComponent: () =>
          import('./features/executions/execution-list').then((m) => m.ExecutionList),
      },
      {
        path: 'executions/:id',
        loadComponent: () =>
          import('./features/executions/execution-detail').then((m) => m.ExecutionDetail),
      },
      {
        path: 'jobs/:id',
        loadComponent: () => import('./features/executions/job-detail').then((m) => m.JobDetail),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
