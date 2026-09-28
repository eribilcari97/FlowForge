import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap, takeWhile, timer } from 'rxjs';

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type JobStatus =
  'PENDING' | 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';

export interface ExecutionSummary {
  id: number;
  workflowId: number;
  workflowName: string;
  runNumber: number;
  status: ExecutionStatus;
  triggerType: 'MANUAL' | 'SCHEDULE';
  createdAt: string;
  finishedAt: string | null;
  errorSummary: string | null;
}

export interface JobSummary {
  id: number;
  stepKey: string;
  jobType: 'HTTP' | 'DELAY';
  status: JobStatus;
  attemptCount: number;
  maxAttempts: number;
  availableAt: string | null;
  lastError: string | null;
  dependsOn: string[];
  startedAt: string | null;
  finishedAt: string | null;
}

export interface Execution extends ExecutionSummary {
  input: Record<string, unknown>;
  jobs: JobSummary[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Attempt {
  number: number;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'ABANDONED';
  workerId: string;
  startedAt: string;
  finishedAt: string | null;
  errorType: string | null;
  errorMessage: string | null;
  retryable: boolean | null;
}

export interface JobDetail {
  id: number;
  executionId: number;
  stepKey: string;
  stepName: string;
  jobType: 'HTTP' | 'DELAY';
  status: JobStatus;
  config: Record<string, unknown>;
  timeoutSeconds: number;
  maxAttempts: number;
  attemptCount: number;
  dependsOn: string[];
  availableAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  output: unknown;
  lastError: string | null;
  attempts: Attempt[];
}

export const POLL_INTERVAL_MS = 2000;

const UNFINISHED_JOB_STATUSES: JobStatus[] = ['PENDING', 'READY', 'RUNNING'];

@Injectable({ providedIn: 'root' })
export class ExecutionService {
  private readonly http = inject(HttpClient);

  start(
    workflowId: number,
    input: Record<string, unknown>,
    idempotencyKey: string,
  ): Observable<Execution> {
    return this.http.post<Execution>(
      `/api/workflows/${workflowId}/executions`,
      { input },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }

  get(id: number): Observable<Execution> {
    return this.http.get<Execution>(`/api/executions/${id}`);
  }

  watch(id: number): Observable<Execution> {
    return timer(0, POLL_INTERVAL_MS).pipe(
      switchMap(() => this.get(id)),
      takeWhile((execution) => execution.status === 'RUNNING', true),
    );
  }

  getJob(id: number): Observable<JobDetail> {
    return this.http.get<JobDetail>(`/api/job-executions/${id}`);
  }

  watchJob(id: number): Observable<JobDetail> {
    return timer(0, POLL_INTERVAL_MS).pipe(
      switchMap(() => this.getJob(id)),
      takeWhile((job) => UNFINISHED_JOB_STATUSES.includes(job.status), true),
    );
  }

  cancel(id: number): Observable<Execution> {
    return this.http.post<Execution>(`/api/executions/${id}/cancel`, null);
  }

  list(
    status: ExecutionStatus | null,
    page: number,
    size: number,
  ): Observable<Page<ExecutionSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<Page<ExecutionSummary>>('/api/executions', { params });
  }

  listOfWorkflow(
    workflowId: number,
    page: number,
    size: number,
  ): Observable<Page<ExecutionSummary>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<ExecutionSummary>>(`/api/workflows/${workflowId}/executions`, {
      params,
    });
  }
}
