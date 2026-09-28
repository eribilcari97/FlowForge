import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatListModule } from '@angular/material/list';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { stepSummary } from './step-config';
import { Step, Workflow, WorkflowService } from './workflow.service';

@Component({
  selector: 'app-workflow-page',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatListModule],
  styleUrl: './workflows.scss',
  template: `
    @if (notFound()) {
      <a mat-button routerLink="/projects">← All projects</a>
      <p class="page-error" role="alert">Workflow not found.</p>
    } @else if (workflow(); as workflow) {
      <a mat-button [routerLink]="['/projects', workflow.projectId]">← Project</a>

      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title>{{ workflow.name }}</mat-card-title>
          <mat-card-subtitle>
            <span class="status" [attr.data-status]="workflow.status">{{ workflow.status }}</span>
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <p class="description">{{ workflow.description || 'No description' }}</p>
        </mat-card-content>
        @if (workflow.status !== 'ARCHIVED') {
          <mat-card-actions>
            <a mat-button [routerLink]="['/workflows', workflow.id, 'edit']">Edit</a>
            <button
              mat-button
              class="danger"
              (click)="deleteWorkflow(workflow)"
              [disabled]="busy()"
            >
              Delete
            </button>
          </mat-card-actions>
        }
      </mat-card>

      <header class="section-header">
        <h2>Steps ({{ workflow.steps.length }}/{{ maxSteps }})</h2>
        @if (workflow.status !== 'ARCHIVED' && workflow.steps.length < maxSteps) {
          <a mat-flat-button [routerLink]="['/workflows', workflow.id, 'steps', 'new']">Add step</a>
        }
      </header>

      @if (error(); as error) {
        <p class="page-error" role="alert">{{ error }}</p>
      }

      @if (workflow.steps.length === 0) {
        <p class="empty">No steps yet. Add an HTTP call or a delay to get started.</p>
      } @else {
        <mat-list>
          @for (step of workflow.steps; track step.id) {
            <mat-list-item class="step">
              <span matListItemTitle>
                <code>{{ step.key }}</code> · {{ step.name }}
                <span class="job-type">{{ step.jobType }}</span>
              </span>
              <span matListItemLine>{{ summary(step) }}</span>
              <span matListItemLine class="limits">
                timeout {{ step.timeoutSeconds }} s · {{ step.maxAttempts }} attempts · retry delay
                {{ step.retryDelaySeconds }} s
              </span>
              @if (workflow.status !== 'ARCHIVED') {
                <span matListItemMeta class="step-actions">
                  <a
                    mat-button
                    [routerLink]="['/workflows', workflow.id, 'steps', step.id, 'edit']"
                  >
                    Edit
                  </a>
                  <button
                    mat-button
                    class="danger"
                    (click)="deleteStep(workflow, step)"
                    [disabled]="busy()"
                  >
                    Delete
                  </button>
                </span>
              }
            </mat-list-item>
          }
        </mat-list>
      }
    } @else if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    } @else {
      <p>Loading…</p>
    }
  `,
})
export class WorkflowPage {
  private readonly workflows = inject(WorkflowService);
  private readonly router = inject(Router);

  protected readonly maxSteps = 30;
  protected readonly workflow = signal<Workflow | null>(null);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => this.workflows.get(Number(params.get('id')))),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (workflow) => this.workflow.set(workflow),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Workflow could not be loaded.'),
      });
  }

  protected summary(step: Step): string {
    return stepSummary(step);
  }

  deleteWorkflow(workflow: Workflow): void {
    if (!confirm(`Delete workflow "${workflow.name}" and all of its steps?`)) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.workflows.delete(workflow.id).subscribe({
      next: () => this.router.navigate(['/projects', workflow.projectId]),
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(problemOf(error)?.detail ?? 'Workflow could not be deleted.');
      },
    });
  }

  deleteStep(workflow: Workflow, step: Step): void {
    if (!confirm(`Delete step "${step.key}"?`)) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.workflows.deleteStep(workflow.id, step.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.workflow.set({ ...workflow, steps: workflow.steps.filter((s) => s.id !== step.id) });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(problemOf(error)?.detail ?? 'Step could not be deleted.');
      },
    });
  }
}
