import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatListModule } from '@angular/material/list';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, switchMap } from 'rxjs';

import { Problem, problemOf } from '../../core/api/problem';
import { DependencyGraph } from '../../shared/dependency-graph';
import { GraphEdgeInput, GraphNodeInput } from '../../shared/graph-layout';
import { StatusBadge } from '../../shared/status-badge';
import { Execution, ExecutionService, ExecutionSummary } from '../executions/execution.service';
import { RunDialog, RunDialogData } from '../executions/run-dialog';
import { ScheduleList } from '../schedules/schedule-list';
import { DependencyPicker } from './dependency-picker';
import { stepSummary } from './step-config';
import { dependencyKeys } from './workflow-graph';
import { Step, Workflow, WorkflowService } from './workflow.service';

const RECENT_RUNS = 5;

@Component({
  selector: 'app-workflow-page',
  imports: [
    RouterLink,
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatListModule,
    DependencyPicker,
    StatusBadge,
    MatTabsModule,
    ScheduleList,
    DependencyGraph,
  ],
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
          @if (problems().length > 0) {
            <div class="problems" role="alert">
              <p>The workflow can't be activated yet:</p>
              <ul>
                @for (problem of problems(); track $index) {
                  <li>
                    @if (problem.stepKey) {
                      <code>{{ problem.stepKey }}</code
                      >:
                    }
                    {{ problem.message }}
                  </li>
                }
              </ul>
            </div>
          }
        </mat-card-content>
        @if (workflow.status !== 'ARCHIVED') {
          <mat-card-actions>
            @if (workflow.status === 'DRAFT') {
              <button
                mat-flat-button
                class="activate"
                (click)="activate(workflow)"
                [disabled]="busy()"
              >
                Activate
              </button>
            } @else {
              <button mat-flat-button class="run" (click)="run(workflow)">Run</button>
              <button
                mat-button
                class="deactivate"
                (click)="deactivate(workflow)"
                [disabled]="busy()"
              >
                Back to draft
              </button>
            }
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
        <h2>Recent runs</h2>
        <a mat-button routerLink="/executions">All executions</a>
      </header>
      @if (recentRuns().length === 0) {
        <p class="empty">This workflow has not run yet.</p>
      } @else {
        <mat-nav-list class="recent-runs">
          @for (run of recentRuns(); track run.id) {
            <a mat-list-item [routerLink]="['/executions', run.id]">
              <span matListItemTitle>
                Run #{{ run.runNumber }} <app-status-badge [status]="run.status" />
              </span>
              <span matListItemLine>{{ run.createdAt | date: 'medium' }}</span>
            </a>
          }
        </mat-nav-list>
      }

      <mat-tab-group class="workflow-tabs" animationDuration="0ms">
        <mat-tab label="Steps ({{ workflow.steps.length }}/{{ maxSteps }})">
          <header class="section-header">
            <h2>Steps</h2>
            @if (workflow.status !== 'ARCHIVED' && workflow.steps.length < maxSteps) {
              <a mat-flat-button [routerLink]="['/workflows', workflow.id, 'steps', 'new']"
                >Add step</a
              >
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
                  <span matListItemLine class="dependencies">{{
                    dependenciesText(workflow, step)
                  }}</span>
                  <span matListItemLine class="limits">
                    timeout {{ step.timeoutSeconds }} s · {{ step.maxAttempts }} attempts · retry
                    delay {{ step.retryDelaySeconds }} s
                  </span>
                  @if (workflow.status !== 'ARCHIVED') {
                    <span matListItemMeta class="step-actions">
                      <button
                        mat-button
                        class="edit-dependencies"
                        (click)="editingDependencies.set(step.id)"
                      >
                        Dependencies
                      </button>
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
                @if (editingDependencies() === step.id) {
                  <app-dependency-picker
                    [workflowId]="workflow.id"
                    [steps]="workflow.steps"
                    [step]="step"
                    (saved)="dependenciesSaved(workflow, step, $event)"
                    (cancelled)="editingDependencies.set(null)"
                  />
                }
              }
            </mat-list>
          }
        </mat-tab>
        <mat-tab label="Graph">
          <ng-template matTabContent>
            <app-dependency-graph
              [nodes]="graphNodes(workflow)"
              [edges]="graphEdges(workflow)"
              [clickable]="workflow.status !== 'ARCHIVED'"
              (nodeClick)="openStep(workflow, $event)"
            />
          </ng-template>
        </mat-tab>
        <mat-tab label="Schedules">
          <ng-template matTabContent>
            <app-schedule-list [workflowId]="workflow.id" [workflowStatus]="workflow.status" />
          </ng-template>
        </mat-tab>
      </mat-tab-group>
    } @else if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    } @else {
      <p>Loading…</p>
    }
  `,
})
export class WorkflowPage {
  private readonly workflows = inject(WorkflowService);
  private readonly executions = inject(ExecutionService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly maxSteps = 30;
  protected readonly workflow = signal<Workflow | null>(null);
  protected readonly recentRuns = signal<ExecutionSummary[]>([]);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly problems = signal<NonNullable<Problem['problems']>>([]);
  protected readonly busy = signal(false);
  protected readonly editingDependencies = signal<number | null>(null);

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => {
          const id = Number(params.get('id'));
          return forkJoin({
            workflow: this.workflows.get(id),
            runs: this.executions.listOfWorkflow(id, 0, RECENT_RUNS),
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ workflow, runs }) => {
          this.workflow.set(workflow);
          this.recentRuns.set(runs.items);
        },
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Workflow could not be loaded.'),
      });
  }

  protected summary(step: Step): string {
    return stepSummary(step);
  }

  protected graphNodes(workflow: Workflow): GraphNodeInput[] {
    return workflow.steps.map((step) => ({
      id: String(step.id),
      label: step.key,
      detail: `${step.jobType} · ${step.name}`,
    }));
  }

  protected graphEdges(workflow: Workflow): GraphEdgeInput[] {
    return workflow.steps.flatMap((step) =>
      step.dependsOn.map((dependencyId) => ({ from: String(dependencyId), to: String(step.id) })),
    );
  }

  openStep(workflow: Workflow, stepId: string): void {
    this.router.navigate(['/workflows', workflow.id, 'steps', Number(stepId), 'edit']);
  }

  run(workflow: Workflow): void {
    this.dialog
      .open<RunDialog, RunDialogData, Execution>(RunDialog, {
        data: { workflowId: workflow.id, workflowName: workflow.name },
      })
      .afterClosed()
      .subscribe((started) => {
        if (started) {
          this.router.navigate(['/executions', started.id]);
        }
      });
  }

  protected dependenciesText(workflow: Workflow, step: Step): string {
    const keys = dependencyKeys(workflow.steps, step);
    return keys.length === 0 ? 'Starts immediately' : `After ${keys.join(', ')}`;
  }

  activate(workflow: Workflow): void {
    this.busy.set(true);
    this.error.set(null);
    this.problems.set([]);
    this.workflows.activate(workflow.id).subscribe({
      next: (activated) => {
        this.busy.set(false);
        this.workflow.set(activated);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        const problem = problemOf(error);
        if (problem?.problems?.length) {
          this.problems.set(problem.problems);
        } else {
          this.error.set(problem?.detail ?? 'Workflow could not be activated.');
        }
      },
    });
  }

  deactivate(workflow: Workflow): void {
    this.busy.set(true);
    this.error.set(null);
    this.workflows.deactivate(workflow.id).subscribe({
      next: (deactivated) => {
        this.busy.set(false);
        this.workflow.set(deactivated);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(problemOf(error)?.detail ?? 'Workflow could not be deactivated.');
      },
    });
  }

  dependenciesSaved(workflow: Workflow, step: Step, dependsOn: number[]): void {
    this.editingDependencies.set(null);
    this.problems.set([]);
    this.workflow.set({
      ...workflow,
      steps: workflow.steps.map((s) => (s.id === step.id ? { ...s, dependsOn } : s)),
    });
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
