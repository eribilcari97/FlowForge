import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap, timer } from 'rxjs';

import { ExecutionSummary, Page } from '../executions/execution.service';

export interface UpcomingRun {
  scheduleId: number;
  workflowId: number;
  workflowName: string;
  cronExpression: string;
  timezone: string;
  nextRunAt: string;
}

export interface Dashboard {
  running: Page<ExecutionSummary>;
  failedLast24Hours: Page<ExecutionSummary>;
  upcomingRuns: UpcomingRun[];
}

export const DASHBOARD_REFRESH_MS = 10_000;

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  get(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/dashboard');
  }

  watch(): Observable<Dashboard> {
    return timer(0, DASHBOARD_REFRESH_MS).pipe(switchMap(() => this.get()));
  }
}
