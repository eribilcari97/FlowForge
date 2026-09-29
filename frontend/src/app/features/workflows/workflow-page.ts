import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, switchMap } from 'rxjs';

import { Problem, problemOf } from '../../core/api/problem';
import { DependencyGraph } from '../../shared/dependency-graph';
import { GraphEdgeInput, GraphNodeInput } from '../../shared/graph-layout';
import { clock } from '../../shared/clock';
import { StatusBadge } from '../../shared/status-badge';
import { elapsedMs, formatDuration, plural, relativeTime } from '../../shared/time';
import { Execution, ExecutionService, ExecutionSummary } from '../executions/execution.service';
import { RunDialog, RunDialogData } from '../executions/run-dialog';
import { ProjectService } from '../projects/project.service';
import { ScheduleList } from '../schedules/schedule-list';
import { Schedule, ScheduleService } from '../schedules/schedule.service';
import { DependencyPicker } from './dependency-picker';
import { stepSummary } from './step-config';
import { dependencyKeys, executionStages } from './workflow-graph';
import { triggerSummary } from './workflow-state';
import { Step, Workflow, WorkflowService } from './workflow.service';

const RECENT_RUNS = 5;

interface Phase {
  name: string;
  detail: string;
  state: 'done' | 'current' | 'todo';
  tab: number | null;
  link: unknown[] | null;
}

@Component({
  selector: 'app-workflow-page',
  imports: [
    RouterLink,
    MatButtonModule,
    DependencyPicker,
    StatusBadge,
    MatTabsModule,
    ScheduleList,
    DependencyGraph,
  ],
  styleUrl: './workflow-page.scss',
  template: `
    @if (notFound()) {
      <a class="crumb" routerLink="/workflows">Workflows</a>
      <p class="alert" role="alert">Workflow not found.</p>
    } @else if (workflow(); as workflow) {
      <a class="crumb" routerLink="/workflows">Workflows</a>

      <header class="entity-header">
        <div class="title">
          <h1>{{ workflow.name }}</h1>
          <div class="entity-meta">
            <app-status-badge class="status" [status]="workflow.status" />
            @if (projectName(); as projectName) {
              <span
                >In <a [routerLink]="['/projects', workflow.projectId]">{{ projectName }}</a></span
              >
            }
          </div>
          @if (workflow.description) {
            <p class="description">{{ workflow.description }}</p>
          }
        </div>
        @if (workflow.status !== 'ARCHIVED') {
          <div class="entity-actions">
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
              <button mat-flat-button class="run" (click)="run(workflow)">Run now</button>
              <button
                mat-stroked-button
                class="deactivate"
                (click)="deactivate(workflow)"
                [disabled]="busy()"
              >
                Back to draft
              </button>
            }
            <a mat-stroked-button [routerLink]="['/workflows', workflow.id, 'edit']"
              >Edit details</a
            >
            <button
              mat-button
              class="danger"
              (click)="deleteWorkflow(workflow)"
              [disabled]="busy()"
            >
              Delete
            </button>
          </div>
        }
      </header>

      @if (problems().length > 0) {
        <div class="alert problems" role="alert">
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

      <ol class="lifecycle" aria-label="Workflow lifecycle">
        @for (phase of lifecycle(); track phase.name) {
          <li [attr.data-state]="phase.state">
            @if (phase.tab !== null) {
              <button type="button" class="phase" (click)="selectedTab.set(phase.tab)">
                <span class="phase-name">{{ phase.name }}</span
                >&ngsp;
                <span class="phase-detail">{{ phase.detail }}</span>
              </button>
            } @else if (phase.link) {
              <a class="phase" [routerLink]="phase.link">
                <span class="phase-name">{{ phase.name }}</span
                >&ngsp;
                <span class="phase-detail">{{ phase.detail }}</span>
              </a>
            } @else {
              <div class="phase">
                <span class="phase-name">{{ phase.name }}</span
                >&ngsp;
                <span class="phase-detail">{{ phase.detail }}</span>
              </div>
            }
          </li>
        }
      </ol>

      @if (error(); as error) {
        <p class="alert" role="alert">{{ error }}</p>
      }

      <div class="workflow-layout">
        <mat-tab-group
          class="workflow-tabs"
          animationDuration="0ms"
          mat-stretch-tabs="false"
          [selectedIndex]="selectedTab()"
          (selectedIndexChange)="selectedTab.set($event)"
        >
          <mat-tab label="Steps ({{ workflow.steps.length }}/{{ maxSteps }})">
            <header class="section-header tab-header">
              <p class="muted">
                Steps run in stages. A step starts as soon as every step it depends on has
                succeeded; steps in the same stage run in parallel.
              </p>
              @if (workflow.status !== 'ARCHIVED' && workflow.steps.length < maxSteps) {
                <a mat-flat-button [routerLink]="['/workflows', workflow.id, 'steps', 'new']"
                  >Add step</a
                >
              }
            </header>

            @if (workflow.steps.length === 0) {
              <div class="panel blank">
                <h3>No steps yet</h3>
                <p class="muted">
                  Add an HTTP request, a JSONata transform, an email or a delay. You can connect
                  steps afterwards so they wait for each other.
                </p>
              </div>
            } @else {
              <ol class="stages">
                @for (stage of stages(); track $index; let index = $index) {
                  <li class="stage">
                    <p class="stage-label">
                      <strong>Stage {{ index + 1 }}</strong>
                      <span class="muted">{{
                        index === 0 ? 'starts when the run starts' : 'after stage ' + index
                      }}</span>
                      @if (stage.length > 1) {
                        <span class="muted">{{ stage.length }} in parallel</span>
                      }
                    </p>
                    <ul class="steps">
                      @for (step of stage; track step.id) {
                        <li class="step">
                          <div class="step-main">
                            <p class="step-title">
                              <span class="job-type">{{ step.jobType }}</span>
                              <code class="key">{{ step.key }}</code>
                            </p>
                            <p class="name">{{ step.name }}</p>
                            <p class="summary">{{ summary(step) }}</p>
                            <p class="dependencies">{{ dependenciesText(workflow, step) }}</p>
                            <p class="limits">
                              Timeout {{ step.timeoutSeconds }} s, {{ step.maxAttempts }}
                              {{ step.maxAttempts === 1 ? 'attempt' : 'attempts' }}, retry delay
                              {{ step.retryDelaySeconds }} s
                            </p>
                          </div>
                          @if (workflow.status !== 'ARCHIVED') {
                            <div class="step-actions">
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
                            </div>
                          }
                          @if (editingDependencies() === step.id) {
                            <app-dependency-picker
                              [workflowId]="workflow.id"
                              [steps]="workflow.steps"
                              [step]="step"
                              (saved)="dependenciesSaved(workflow, step, $event)"
                              (cancelled)="editingDependencies.set(null)"
                            />
                          }
                        </li>
                      }
                    </ul>
                  </li>
                }
              </ol>
            }
          </mat-tab>
          <mat-tab label="Graph">
            <ng-template matTabContent>
              @if (workflow.status !== 'ARCHIVED') {
                <p class="tab-note muted">Select a step to edit it.</p>
              }
              <app-dependency-graph
                [nodes]="graphNodes(workflow)"
                [edges]="graphEdges(workflow)"
                [clickable]="workflow.status !== 'ARCHIVED'"
                (nodeClick)="openStep(workflow, $event)"
              />
            </ng-template>
          </mat-tab>
          <mat-tab label="Schedules ({{ schedules().length }})">
            <ng-template matTabContent>
              <app-schedule-list
                [workflowId]="workflow.id"
                [workflowStatus]="workflow.status"
                (changed)="schedules.set($event)"
              />
            </ng-template>
          </mat-tab>
        </mat-tab-group>

        <aside class="recent" aria-labelledby="recent-title">
          <header class="section-header">
            <h2 id="recent-title">Recent runs</h2>
            @if (totalRuns() > recentRuns().length) {
              <a routerLink="/executions">All runs</a>
            }
          </header>
          @if (recentRuns().length === 0) {
            <p class="empty">
              {{
                workflow.status === 'ACTIVE'
                  ? 'Not run yet. Use Run now, or add a schedule.'
                  : 'Runs appear here once the workflow is active and has been started.'
              }}
            </p>
          } @else {
            <ul class="run-list panel recent-runs">
              @for (run of recentRuns(); track run.id) {
                <li>
                  <a
                    class="run-row"
                    [attr.data-status]="run.status"
                    [routerLink]="['/executions', run.id]"
                  >
                    <span class="run-title"
                      >Run #{{ run.runNumber }} <app-status-badge [status]="run.status"
                    /></span>
                    <span class="run-meta"
                      >{{ relative(run.createdAt) }}, {{ runDuration(run)
                      }}{{ run.triggerType === 'SCHEDULE' ? ', by schedule' : '' }}</span
                    >
                  </a>
                </li>
              }
            </ul>
          }
        </aside>
      </div>
    } @else if (error(); as error) {
      <p class="alert" role="alert">{{ error }}</p>
    } @else {
      <p class="muted">Loading workflow…</p>
    }
  `,
})
export class WorkflowPage {
  private readonly workflows = inject(WorkflowService);
  private readonly executions = inject(ExecutionService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly projectService = inject(ProjectService);
  private readonly scheduleService = inject(ScheduleService);

  protected readonly maxSteps = 30;
  protected readonly workflow = signal<Workflow | null>(null);
  protected readonly recentRuns = signal<ExecutionSummary[]>([]);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly problems = signal<NonNullable<Problem['problems']>>([]);
  protected readonly busy = signal(false);
  protected readonly editingDependencies = signal<number | null>(null);
  protected readonly schedules = signal<Schedule[]>([]);
  protected readonly totalRuns = signal(0);
  protected readonly projectName = signal<string | null>(null);
  protected readonly selectedTab = signal(0);
  protected readonly now = clock(15_000);

  protected readonly stages = computed(() => executionStages(this.workflow()?.steps ?? []));

  protected readonly lifecycle = computed<Phase[]>(() => {
    const workflow = this.workflow();
    if (!workflow) {
      return [];
    }
    const stepCount = workflow.steps.length;
    const trigger = triggerSummary(workflow.status, this.schedules(), this.now());
    const lastRun = this.recentRuns()[0];
    const done = [stepCount > 0, workflow.status === 'ACTIVE', true, this.totalRuns() > 0];
    const current = done.findIndex((isDone) => !isDone);
    const state = (index: number): Phase['state'] =>
      done[index] && index !== current ? 'done' : index === current ? 'current' : 'todo';
    return [
      {
        name: 'Steps',
        detail:
          stepCount === 0
            ? 'Add the first step'
            : `${plural(stepCount, 'step')} in ${plural(this.stages().length, 'stage')}`,
        state: state(0),
        tab: 0,
        link: null,
      },
      {
        name: 'Activate',
        detail:
          workflow.status === 'ACTIVE'
            ? 'Active and ready to run'
            : workflow.status === 'ARCHIVED'
              ? 'Archived, read only'
              : 'Draft: activate to run it',
        state: state(1),
        tab: null,
        link: null,
      },
      {
        name: 'Trigger',
        detail: trigger.detail ? `${trigger.label}. ${trigger.detail}` : trigger.label,
        state: state(2),
        tab: 2,
        link: null,
      },
      {
        name: 'Runs',
        detail: lastRun
          ? `${plural(this.totalRuns(), 'run')}, last ${lastRun.status === 'RUNNING' ? 'started' : lastRun.status.toLowerCase()} ${relativeTime(lastRun.createdAt, this.now())}`
          : 'Not run yet',
        state: state(3),
        tab: null,
        link: lastRun ? ['/executions', lastRun.id] : null,
      },
    ];
  });

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => {
          const id = Number(params.get('id'));
          return forkJoin({
            workflow: this.workflows.get(id),
            runs: this.executions.listOfWorkflow(id, 0, RECENT_RUNS),
            schedules: this.scheduleService.list(id),
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ workflow, runs, schedules }) => {
          this.workflow.set(workflow);
          this.recentRuns.set(runs.items);
          this.totalRuns.set(runs.total);
          this.schedules.set(schedules);
          this.loadProjectName(workflow.projectId);
        },
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Workflow could not be loaded.'),
      });
  }

  protected relative(iso: string): string {
    return relativeTime(iso, this.now());
  }

  protected runDuration(run: ExecutionSummary): string {
    const ms = elapsedMs(run.createdAt, run.finishedAt, this.now());
    return run.finishedAt
      ? `took ${formatDuration(ms ?? 0)}`
      : `running for ${formatDuration(ms ?? 0)}`;
  }

  private loadProjectName(projectId: number): void {
    this.projectService.get(projectId).subscribe({
      next: (project) => this.projectName.set(project.name),
      error: () => this.projectName.set(null),
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
