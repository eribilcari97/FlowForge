import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { switchMap, tap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { clock } from '../../shared/clock';
import { WorkflowCard } from '../workflows/workflow-card';
import { WorkflowOverview, WorkflowOverviewService } from '../workflows/workflow-overview.service';
import { Project, ProjectService } from './project.service';

@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, DatePipe, MatButtonModule, WorkflowCard],
  styleUrl: './projects.scss',
  template: `
    <a class="crumb" routerLink="/projects">Projects</a>

    @if (notFound()) {
      <p class="alert" role="alert">Project not found.</p>
    } @else if (project(); as project) {
      <header class="entity-header">
        <div class="title">
          <h1>{{ project.name }}</h1>
          <p class="description">{{ project.description || 'No description' }}</p>
          <div class="entity-meta">
            <span>Created {{ project.createdAt | date: 'mediumDate' }}</span>
            <span
              >{{ project.workflowCount }}
              {{ project.workflowCount === 1 ? 'workflow' : 'workflows' }}</span
            >
          </div>
        </div>
        <div class="entity-actions">
          <a mat-stroked-button [routerLink]="['/projects', project.id, 'edit']">Edit</a>
          <button mat-button class="danger" (click)="delete(project)" [disabled]="deleting()">
            Delete
          </button>
        </div>
      </header>

      @if (error(); as error) {
        <p class="alert" role="alert">{{ error }}</p>
      }

      <header class="section-header">
        <h2>Workflows</h2>
        <a mat-flat-button routerLink="/workflows/new" [queryParams]="{ projectId: project.id }"
          >New workflow</a
        >
      </header>
      @if (workflows(); as workflows) {
        @if (workflows.length === 0) {
          <div class="panel blank">
            <p class="description">
              No workflows in this project yet. Start blank or from one of the examples.
            </p>
            <a
              mat-stroked-button
              routerLink="/workflows/new"
              [queryParams]="{ projectId: project.id }"
              >Create workflow</a
            >
          </div>
        } @else {
          <ul class="card-grid">
            @for (overview of workflows; track overview.workflow.id) {
              <li><app-workflow-card [overview]="overview" [now]="now()" /></li>
            }
          </ul>
        }
      } @else {
        <p class="muted">Loading workflows…</p>
      }
    } @else if (error(); as error) {
      <p class="alert" role="alert">{{ error }}</p>
    } @else {
      <p class="muted">Loading project…</p>
    }
  `,
})
export class ProjectDetail {
  private readonly projects = inject(ProjectService);
  private readonly overviews = inject(WorkflowOverviewService);
  private readonly router = inject(Router);

  protected readonly project = signal<Project | null>(null);
  protected readonly workflows = signal<WorkflowOverview[] | null>(null);
  protected readonly now = clock(15_000);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly deleting = signal(false);

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => this.projects.get(Number(params.get('id')))),
        tap((project) => this.project.set(project)),
        switchMap((project) => this.overviews.forProjects([project])),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (workflows) => this.workflows.set(workflows),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Project could not be loaded.'),
      });
  }

  delete(project: Project): void {
    if (!confirm(`Delete project "${project.name}"?`)) {
      return;
    }
    this.deleting.set(true);
    this.error.set(null);
    this.projects.delete(project.id).subscribe({
      next: () => this.router.navigate(['/projects']),
      error: (error: unknown) => {
        this.deleting.set(false);
        this.error.set(problemOf(error)?.detail ?? 'Project could not be deleted.');
      },
    });
  }
}
