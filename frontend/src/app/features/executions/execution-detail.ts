import { DatePipe, JsonPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { interval, map, switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { DependencyGraph } from '../../shared/dependency-graph';
import { GraphEdgeInput, GraphNodeInput } from '../../shared/graph-layout';
import { StatusBadge } from '../../shared/status-badge';
import { elapsedMs, formatDuration } from '../../shared/time';
import { Execution, ExecutionService, JobSummary } from './execution.service';
import { TimelineBar, retryCountdown, timelineBar } from './job-status';
import { RunDialog, RunDialogData } from './run-dialog';

@Component({
  selector: 'app-execution-detail',
  imports: [
    RouterLink,
    DatePipe,
    JsonPipe,
    MatButtonModule,
    MatButtonToggleModule,
    StatusBadge,
    DependencyGraph,
  ],
  styleUrl: './executions.scss',
  template: `
    @if (notFound()) {
      <a class="crumb" routerLink="/executions">Runs</a>
      <p class="alert" role="alert">Run not found.</p>
    } @else if (execution(); as execution) {
      <a class="crumb" [routerLink]="['/workflows', execution.workflowId]">{{
        execution.workflowName
      }}</a>

      <header class="entity-header">
        <div class="title">
          <h1>Run #{{ execution.runNumber }}</h1>
          <div class="entity-meta">
            <app-status-badge [status]="execution.status" />
            @if (execution.status === 'RUNNING') {
              <span class="refresh-note">Live, refreshing every 2 seconds</span>
            }
          </div>
        </div>
        <div class="entity-actions">
          @if (execution.status === 'RUNNING') {
            <button
              mat-button
              class="danger cancel"
              (click)="cancel(execution)"
              [disabled]="busy()"
            >
              Cancel run
            </button>
          }
          <button mat-flat-button class="run-again" (click)="runAgain(execution)">Run again</button>
        </div>
      </header>

      <dl class="facts" [attr.data-status]="execution.status">
        <div>
          <dt>Duration</dt>
          <dd class="duration">{{ duration(execution.createdAt, execution.finishedAt) }}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd>{{ execution.createdAt | date: 'MMM d, HH:mm:ss' }}</dd>
        </div>
        <div>
          <dt>Finished</dt>
          <dd>
            {{ execution.finishedAt ? (execution.finishedAt | date: 'MMM d, HH:mm:ss') : '—' }}
          </dd>
        </div>
        <div>
          <dt>Trigger</dt>
          <dd>{{ execution.triggerType === 'MANUAL' ? 'Manual' : 'Schedule' }}</dd>
        </div>
        <div>
          <dt>Jobs</dt>
          <dd>{{ progress(execution) }}</dd>
        </div>
      </dl>

      @if (execution.errorSummary) {
        <p class="alert">{{ execution.errorSummary }}</p>
      }
      @if (error(); as error) {
        <p class="alert" role="alert">{{ error }}</p>
      }

      <section aria-labelledby="jobs-title">
        <header class="section-header first">
          <h2 id="jobs-title">Jobs</h2>
          <mat-button-toggle-group
            class="job-filter"
            hideSingleSelectionIndicator
            [value]="onlyFailed()"
            (change)="onlyFailed.set($event.value)"
          >
            <mat-button-toggle [value]="false">All ({{ execution.jobs.length }})</mat-button-toggle>
            <mat-button-toggle [value]="true" class="failed-filter"
              >Failed ({{ failedCount(execution) }})</mat-button-toggle
            >
          </mat-button-toggle-group>
        </header>
        <div class="table-scroll">
          <table class="jobs">
            <thead>
              <tr>
                <th>Step</th>
                <th>Status</th>
                <th>Attempts</th>
                <th>Duration</th>
                <th class="timeline-head">
                  Timeline
                  <span class="scale"
                    >0 to {{ duration(execution.createdAt, execution.finishedAt) }}</span
                  >
                </th>
              </tr>
            </thead>
            <tbody>
              @for (job of visibleJobs(execution); track job.id) {
                <tr [class.has-error]="job.lastError">
                  <td class="rail" [attr.data-status]="job.status">
                    <a class="job-link" [routerLink]="['/jobs', job.id]"
                      ><code>{{ job.stepKey }}</code></a
                    >
                    <span class="job-meta"
                      >{{ job.jobType
                      }}{{
                        job.dependsOn.length ? ', after ' + job.dependsOn.join(', ') : ''
                      }}</span
                    >
                  </td>
                  <td>
                    <app-status-badge [status]="job.status" />
                    @if (countdown(job); as countdown) {
                      <div class="retry-countdown">{{ countdown }}</div>
                    }
                  </td>
                  <td>{{ job.attemptCount }}/{{ job.maxAttempts }}</td>
                  <td class="job-duration">
                    {{ job.startedAt ? duration(job.startedAt, job.finishedAt) : '—' }}
                  </td>
                  <td class="timeline">
                    <div class="track">
                      @if (bar(execution, job); as bar) {
                        <span
                          class="bar"
                          [attr.data-status]="job.status"
                          [style.left.%]="bar.left"
                          [style.width.%]="bar.width"
                        ></span>
                      } @else {
                        <span class="not-started">Not started</span>
                      }
                    </div>
                  </td>
                </tr>
                @if (job.lastError) {
                  <tr class="error-row">
                    <td colspan="5">{{ job.lastError }}</td>
                  </tr>
                }
              } @empty {
                <tr>
                  <td colspan="5" class="empty">No failed jobs.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <div class="lower">
        <section aria-labelledby="graph-title">
          <header class="section-header">
            <h2 id="graph-title">Graph</h2>
            <span class="muted hint">Select a job to see its attempts and output.</span>
          </header>
          <app-dependency-graph
            class="execution-graph"
            [nodes]="graphNodes(execution)"
            [edges]="graphEdges(execution)"
            [clickable]="true"
            (nodeClick)="openJob(execution, $event)"
          />
        </section>

        <section aria-labelledby="input-title">
          <header class="section-header">
            <h2 id="input-title">Input</h2>
          </header>
          <pre class="json">{{ execution.input | json }}</pre>
        </section>
      </div>
    } @else if (error(); as error) {
      <p class="alert" role="alert">{{ error }}</p>
    } @else {
      <p class="muted">Loading run…</p>
    }
  `,
})
export class ExecutionDetail {
  private readonly executions = inject(ExecutionService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly execution = signal<Execution | null>(null);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly onlyFailed = signal(false);
  private readonly now = toSignal(interval(1000).pipe(map(() => Date.now())), {
    initialValue: Date.now(),
  });

  protected graphNodes(execution: Execution): GraphNodeInput[] {
    return execution.jobs.map((job) => ({
      id: job.stepKey,
      label: job.stepKey,
      detail: `${job.jobType} · ${job.status}`,
      status: job.status,
    }));
  }

  protected graphEdges(execution: Execution): GraphEdgeInput[] {
    return execution.jobs.flatMap((job) =>
      job.dependsOn.map((dependency) => ({ from: dependency, to: job.stepKey })),
    );
  }

  openJob(execution: Execution, stepKey: string): void {
    const job = execution.jobs.find((candidate) => candidate.stepKey === stepKey);
    if (job) {
      this.router.navigate(['/jobs', job.id]);
    }
  }

  protected visibleJobs(execution: Execution): JobSummary[] {
    return this.onlyFailed()
      ? execution.jobs.filter((job) => job.status === 'FAILED')
      : execution.jobs;
  }

  protected failedCount(execution: Execution): number {
    return execution.jobs.filter((job) => job.status === 'FAILED').length;
  }

  protected duration(start: string, end: string | null): string {
    return formatDuration(elapsedMs(start, end, this.now()) ?? 0);
  }

  protected bar(execution: Execution, job: JobSummary): TimelineBar | null {
    return timelineBar(execution, job, this.now());
  }

  protected progress(execution: Execution): string {
    const finished = execution.jobs.filter(
      (job) => !['PENDING', 'READY', 'RUNNING'].includes(job.status),
    ).length;
    return `${finished} of ${execution.jobs.length} finished`;
  }

  protected countdown(job: JobSummary): string | null {
    return retryCountdown(job, this.now());
  }

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => this.executions.watch(Number(params.get('id')))),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (execution) => this.execution.set(execution),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Execution could not be loaded.'),
      });
  }

  cancel(execution: Execution): void {
    if (!confirm(`Cancel run #${execution.runNumber}? Waiting jobs will not start.`)) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.executions.cancel(execution.id).subscribe({
      next: (cancelled) => {
        this.busy.set(false);
        this.execution.set(cancelled);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(problemOf(error)?.detail ?? 'The run could not be cancelled.');
      },
    });
  }

  runAgain(execution: Execution): void {
    const data: RunDialogData = {
      workflowId: execution.workflowId,
      workflowName: execution.workflowName,
      input: execution.input,
    };
    this.dialog
      .open<RunDialog, RunDialogData, Execution>(RunDialog, { data })
      .afterClosed()
      .subscribe((started) => {
        if (started) {
          this.router.navigate(['/executions', started.id]);
        }
      });
  }
}
