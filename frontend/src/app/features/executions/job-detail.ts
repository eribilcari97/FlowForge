import { DatePipe, JsonPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval, map, switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { StatusBadge } from '../../shared/status-badge';
import { ExecutionService, JobDetail as Job } from './execution.service';
import { retryCountdown, retryableLabel } from './job-status';

@Component({
  selector: 'app-job-detail',
  imports: [RouterLink, DatePipe, JsonPipe, MatButtonModule, MatCardModule, StatusBadge],
  styleUrl: './executions.scss',
  template: `
    @if (notFound()) {
      <a mat-button routerLink="/executions">← All executions</a>
      <p class="page-error" role="alert">Job not found.</p>
    } @else if (job(); as job) {
      <a mat-button [routerLink]="['/executions', job.executionId]">← Execution</a>

      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title>
            <code>{{ job.stepKey }}</code> · {{ job.stepName }}
          </mat-card-title>
          <mat-card-subtitle>
            <app-status-badge [status]="job.status" />
            {{ job.jobType }} · attempt {{ job.attemptCount }} of {{ job.maxAttempts }} · timeout
            {{ job.timeoutSeconds }} s
            @if (countdown(job); as countdown) {
              · <span class="retry-countdown">{{ countdown }}</span>
            }
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          @if (job.dependsOn.length) {
            <p>Waits for: {{ job.dependsOn.join(', ') }}</p>
          }
          @if (job.lastError) {
            <p class="page-error last-error">{{ job.lastError }}</p>
          }
          <h3>Output</h3>
          @if (job.output !== null && job.output !== undefined) {
            <pre class="json output">{{ job.output | json }}</pre>
          } @else {
            <p class="empty">No output yet.</p>
          }
          <h3>Configuration used by this run</h3>
          <pre class="json">{{ job.config | json }}</pre>
        </mat-card-content>
      </mat-card>

      <h2>Attempts</h2>
      @if (job.attempts.length === 0) {
        <p class="empty">Not started yet.</p>
      } @else {
        <table class="attempts">
          <thead>
            <tr>
              <th>#</th>
              <th>Status</th>
              <th>Started</th>
              <th>Finished</th>
              <th>Worker</th>
              <th>Error</th>
              <th>Retry?</th>
            </tr>
          </thead>
          <tbody>
            @for (attempt of job.attempts; track attempt.number) {
              <tr>
                <td>{{ attempt.number }}</td>
                <td><app-status-badge [status]="attempt.status" /></td>
                <td>{{ attempt.startedAt | date: 'mediumTime' }}</td>
                <td>{{ attempt.finishedAt ? (attempt.finishedAt | date: 'mediumTime') : '—' }}</td>
                <td>{{ attempt.workerId }}</td>
                <td>
                  @if (attempt.errorType) {
                    <code>{{ attempt.errorType }}</code> {{ attempt.errorMessage }}
                  } @else {
                    —
                  }
                </td>
                <td class="retryable">{{ retryable(attempt.retryable) }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    } @else if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    } @else {
      <p>Loading…</p>
    }
  `,
})
export class JobDetail {
  private readonly executions = inject(ExecutionService);

  protected readonly job = signal<Job | null>(null);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly now = toSignal(interval(1000).pipe(map(() => Date.now())), {
    initialValue: Date.now(),
  });

  protected countdown(job: Job): string | null {
    return retryCountdown(job, this.now());
  }

  protected retryable(value: boolean | null): string {
    return retryableLabel(value);
  }

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        switchMap((params) => this.executions.watchJob(Number(params.get('id')))),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (job) => this.job.set(job),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Job could not be loaded.'),
      });
  }
}
