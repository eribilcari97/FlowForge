import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatListModule } from '@angular/material/list';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { WorkflowService, WorkflowSummary } from '../workflows/workflow.service';
import { Project, ProjectService } from './project.service';

@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, DatePipe, MatButtonModule, MatCardModule, MatListModule],
  styleUrl: './projects.scss',
  template: `
    <a mat-button routerLink="/projects">← All projects</a>

    @if (notFound()) {
      <p class="page-error" role="alert">Project not found.</p>
    } @else if (project(); as project) {
      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title>{{ project.name }}</mat-card-title>
          <mat-card-subtitle>Created {{ project.createdAt | date: 'medium' }}</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <p class="description">{{ project.description || 'No description' }}</p>
          <p>Workflows: {{ project.workflowCount }}</p>
          @if (error(); as error) {
            <p class="page-error" role="alert">{{ error }}</p>
          }
        </mat-card-content>
        <mat-card-actions>
          <a mat-button [routerLink]="['/projects', project.id, 'edit']">Edit</a>
          <button mat-button class="danger" (click)="delete(project)" [disabled]="deleting()">
            Delete
          </button>
        </mat-card-actions>
      </mat-card>

      <header class="page-header">
        <h2>Workflows</h2>
        <a mat-flat-button [routerLink]="['/projects', project.id, 'workflows', 'new']"
          >New workflow</a
        >
      </header>
      @if (workflows().length === 0) {
        <p class="empty">No workflows yet.</p>
      } @else {
        <mat-nav-list>
          @for (workflow of workflows(); track workflow.id) {
            <a mat-list-item [routerLink]="['/workflows', workflow.id]">
              <span matListItemTitle>{{ workflow.name }}</span>
              <span matListItemLine
                >{{ workflow.status }} · {{ workflow.description || 'No description' }}</span
              >
            </a>
          }
        </mat-nav-list>
      }
    } @else if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    } @else {
      <p>Loading…</p>
    }
  `,
})
export class ProjectDetail {
  private readonly projects = inject(ProjectService);
  private readonly workflowService = inject(WorkflowService);
  private readonly router = inject(Router);

  protected readonly project = signal<Project | null>(null);
  protected readonly workflows = signal<WorkflowSummary[]>([]);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly deleting = signal(false);

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => {
          const id = Number(params.get('id'));
          return forkJoin({
            project: this.projects.get(id),
            workflows: this.workflowService.list(id),
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ project, workflows }) => {
          this.project.set(project);
          this.workflows.set(workflows);
        },
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
