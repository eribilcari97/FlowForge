import { DatePipe, JsonPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { StatusBadge } from '../../shared/status-badge';
import { Execution, ExecutionService } from './execution.service';
import { RunDialog, RunDialogData } from './run-dialog';

@Component({
  selector: 'app-execution-detail',
  imports: [RouterLink, DatePipe, JsonPipe, MatButtonModule, MatCardModule, StatusBadge],
  styleUrl: './executions.scss',
  template: `
    @if (notFound()) {
      <a mat-button routerLink="/executions">← All executions</a>
      <p class="page-error" role="alert">Execution not found.</p>
    } @else if (execution(); as execution) {
      <a mat-button [routerLink]="['/workflows', execution.workflowId]"
        >← {{ execution.workflowName }}</a
      >

      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title
            >{{ execution.workflowName }} · run #{{ execution.runNumber }}</mat-card-title
          >
          <mat-card-subtitle>
            <app-status-badge [status]="execution.status" />
            {{ execution.triggerType === 'MANUAL' ? 'Started manually' : 'Started by schedule' }}
            {{ execution.createdAt | date: 'medium' }}
            @if (execution.finishedAt) {
              · finished {{ execution.finishedAt | date: 'medium' }}
            }
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          @if (execution.status === 'RUNNING') {
            <p class="refresh-note">Refreshing every 2 seconds while running.</p>
          }
          @if (execution.errorSummary) {
            <p class="page-error">{{ execution.errorSummary }}</p>
          }
          <h3>Input</h3>
          <pre class="json">{{ execution.input | json }}</pre>
          @if (error(); as error) {
            <p class="page-error" role="alert">{{ error }}</p>
          }
        </mat-card-content>
        <mat-card-actions>
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
          <button mat-button class="run-again" (click)="runAgain(execution)">Run again</button>
        </mat-card-actions>
      </mat-card>

      <h2>Jobs</h2>
      <table class="jobs">
        <thead>
          <tr>
            <th>Step</th>
            <th>Type</th>
            <th>Status</th>
            <th>Attempts</th>
            <th>Waits for</th>
            <th>Available at</th>
            <th>Last error</th>
          </tr>
        </thead>
        <tbody>
          @for (job of execution.jobs; track job.id) {
            <tr>
              <td>
                <a class="job-link" [routerLink]="['/jobs', job.id]"
                  ><code>{{ job.stepKey }}</code></a
                >
              </td>
              <td>{{ job.jobType }}</td>
              <td><app-status-badge [status]="job.status" /></td>
              <td>{{ job.attemptCount }}/{{ job.maxAttempts }}</td>
              <td>{{ job.dependsOn.length ? job.dependsOn.join(', ') : '—' }}</td>
              <td>{{ job.availableAt ? (job.availableAt | date: 'medium') : '—' }}</td>
              <td>{{ job.lastError ?? '—' }}</td>
            </tr>
          }
        </tbody>
      </table>
    } @else if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    } @else {
      <p>Loading…</p>
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
