import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { RouterLink } from '@angular/router';

import { clock } from '../../shared/clock';
import { plural } from '../../shared/time';
import { WorkflowStatus } from './workflow.service';
import { WorkflowCard } from './workflow-card';
import { WorkflowOverviewService, Workspace } from './workflow-overview.service';
import { Welcome } from './welcome';

type Filter = WorkflowStatus | 'ALL';

@Component({
  selector: 'app-workflow-list',
  imports: [RouterLink, MatButtonModule, MatButtonToggleModule, WorkflowCard, Welcome],
  styleUrl: './workflow-list.scss',
  template: `
    @if (error()) {
      <p class="alert" role="alert">
        Your workflows could not be loaded. Refresh the page to try again.
      </p>
    } @else if (workspace(); as workspace) {
      @if (workspace.workflows.length === 0) {
        <app-welcome />
      } @else {
        <header class="page-header">
          <div>
            <h1>Workflows</h1>
            <p class="description">{{ summary() }}</p>
          </div>
          <div class="entity-actions">
            <a mat-stroked-button routerLink="/projects">Manage projects</a>
            <a mat-flat-button routerLink="/workflows/new">New workflow</a>
          </div>
        </header>

        <mat-button-toggle-group
          class="status-filter"
          hideSingleSelectionIndicator
          aria-label="Show workflows"
          [value]="filter()"
          (change)="filter.set($event.value)"
        >
          @for (option of filters(); track option.value) {
            <mat-button-toggle [value]="option.value"
              >{{ option.label }} ({{ option.count }})</mat-button-toggle
            >
          }
        </mat-button-toggle-group>

        @for (group of groups(); track group.project.id) {
          <section class="project-group" [attr.aria-labelledby]="'project-' + group.project.id">
            <header class="section-header">
              <h2 [id]="'project-' + group.project.id">
                <a [routerLink]="['/projects', group.project.id]">{{ group.project.name }}</a>
              </h2>
              <a
                class="add-link"
                routerLink="/workflows/new"
                [queryParams]="{ projectId: group.project.id }"
                >Add workflow</a
              >
            </header>
            @if (group.workflows.length === 0) {
              <p class="empty">No workflows in this project yet.</p>
            } @else {
              <ul class="card-grid">
                @for (overview of group.workflows; track overview.workflow.id) {
                  <li><app-workflow-card [overview]="overview" [now]="now()" /></li>
                }
              </ul>
            }
          </section>
        } @empty {
          <p class="empty">No workflows match this filter.</p>
        }
      }
    } @else {
      <p class="muted">Loading workflows…</p>
    }
  `,
})
export class WorkflowList {
  protected readonly workspace = signal<Workspace | null>(null);
  protected readonly error = signal(false);
  protected readonly filter = signal<Filter>('ALL');
  protected readonly now = clock(15_000);

  protected readonly summary = computed(() => {
    const workspace = this.workspace()!;
    const active = workspace.workflows.filter((o) => o.workflow.status === 'ACTIVE').length;
    return `${plural(workspace.workflows.length, 'workflow')} in ${plural(workspace.projects.length, 'project')}, ${active} active.`;
  });

  protected readonly filters = computed(() => {
    const workflows = this.workspace()?.workflows ?? [];
    const count = (status: WorkflowStatus) =>
      workflows.filter((o) => o.workflow.status === status).length;
    const options: { value: Filter; label: string; count: number }[] = [
      { value: 'ALL', label: 'All', count: workflows.length },
      { value: 'ACTIVE', label: 'Active', count: count('ACTIVE') },
      { value: 'DRAFT', label: 'Inactive', count: count('DRAFT') },
    ];
    if (count('ARCHIVED') > 0) {
      options.push({ value: 'ARCHIVED', label: 'Archived', count: count('ARCHIVED') });
    }
    return options;
  });

  protected readonly groups = computed(() => {
    const workspace = this.workspace();
    if (!workspace) {
      return [];
    }
    const filter = this.filter();
    return workspace.projects
      .map((project) => ({
        project,
        workflows: workspace.workflows.filter(
          (o) => o.project.id === project.id && (filter === 'ALL' || o.workflow.status === filter),
        ),
      }))
      .filter((group) => filter === 'ALL' || group.workflows.length > 0);
  });

  constructor() {
    inject(WorkflowOverviewService)
      .load()
      .subscribe({
        next: (workspace) => this.workspace.set(workspace),
        error: () => this.error.set(true),
      });
  }
}
