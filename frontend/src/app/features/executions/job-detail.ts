import { DatePipe, JsonPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval, map, switchMap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { StatusBadge } from '../../shared/status-badge';
import { elapsedMs, formatDuration } from '../../shared/time';
import { ExecutionService, JobDetail as Job } from './execution.service';
import { retryCountdown, retryableLabel } from './job-status';

@Component({
  selector: 'app-job-detail',
  imports: [RouterLink, DatePipe, JsonPipe, StatusBadge],
  styleUrl: './executions.scss',
  template: `
    @if (notFound()) {
      <a class="crumb" routerLink="/executions">Runs</a>
      <p class="alert" role="alert">Job not found.</p>
    } @else if (job(); as job) {
      <a class="crumb" [routerLink]="['/executions', job.executionId]">Run</a>

      <header class="entity-header">
        <div class="title">
          <h1>
            <code class="step-key">{{ job.stepKey }}</code> {{ job.stepName }}
          </h1>
          <div class="entity-meta">
            <app-status-badge [status]="job.status" />
            <span>{{ job.jobType }}</span>
            <span>attempt {{ job.attemptCount }} of {{ job.maxAttempts }}</span>
            <span>timeout {{ job.timeoutSeconds }} s</span>
            @if (job.startedAt) {
              <span
                >{{ job.finishedAt ? 'took' : 'running for' }}
                {{ duration(job.startedAt, job.finishedAt) }}</span
              >
            }
            @if (countdown(job); as countdown) {
              <span class="retry-countdown">{{ countdown }}</span>
            }
          </div>
          @if (job.dependsOn.length) {
            <p class="description">Waits for {{ job.dependsOn.join(', ') }}</p>
          }
        </div>
      </header>

      @if (job.lastError) {
        <p class="alert last-error">{{ job.lastError }}</p>
      }

      <div class="io">
        <section aria-labelledby="output-title">
          <h2 id="output-title">Output</h2>
          @if (job.output !== null && job.output !== undefined) {
            <pre class="json output">{{ job.output | json }}</pre>
          } @else {
            <p class="empty">No output yet.</p>
          }
        </section>
        <section aria-labelledby="config-title">
          <h2 id="config-title">Configuration used by this run</h2>
          <pre class="json">{{ job.config | json }}</pre>
        </section>
      </div>

      <header class="section-header">
        <h2>Attempts</h2>
      </header>
      @if (job.attempts.length === 0) {
        <p class="empty">Not started yet.</p>
      } @else {
        <div class="table-scroll">
          <table class="attempts">
            <thead>
              <tr>
                <th>#</th>
                <th>Status</th>
                <th>Started</th>
                <th>Duration</th>
                <th>Worker</th>
                <th>Error</th>
                <th>Retry?</th>
              </tr>
            </thead>
            <tbody>
              @for (attempt of job.attempts; track attempt.number) {
                <tr>
                  <td class="rail" [attr.data-status]="attempt.status">{{ attempt.number }}</td>
                  <td><app-status-badge [status]="attempt.status" /></td>
                  <td>{{ attempt.startedAt | date: 'mediumTime' }}</td>
                  <td>{{ duration(attempt.startedAt, attempt.finishedAt) }}</td>
                  <td>
                    <code>{{ attempt.workerId }}</code>
                  </td>
                  <td [class.error-cell]="attempt.errorType">
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
        </div>
      }
    } @else if (error(); as error) {
      <p class="alert" role="alert">{{ error }}</p>
    } @else {
      <p class="muted">Loading job…</p>
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

  protected duration(start: string, end: string | null): string {
    const ms = formatDuration(elapsedMs(start, end, this.now()) ?? 0);
    return end ? ms : `${ms} so far`;
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
