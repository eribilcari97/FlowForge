import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Schedule {
  id: number;
  workflowId: number;
  cronExpression: string;
  timezone: string;
  input: Record<string, unknown>;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
}

export interface ScheduleRequest {
  cronExpression: string;
  timezone: string;
  input: Record<string, unknown>;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class ScheduleService {
  private readonly http = inject(HttpClient);

  list(workflowId: number): Observable<Schedule[]> {
    return this.http.get<Schedule[]>(`/api/workflows/${workflowId}/schedules`);
  }

  create(workflowId: number, request: ScheduleRequest): Observable<Schedule> {
    return this.http.post<Schedule>(`/api/workflows/${workflowId}/schedules`, request);
  }

  update(workflowId: number, scheduleId: number, request: ScheduleRequest): Observable<Schedule> {
    return this.http.put<Schedule>(`/api/workflows/${workflowId}/schedules/${scheduleId}`, request);
  }

  delete(workflowId: number, scheduleId: number): Observable<void> {
    return this.http.delete<void>(`/api/workflows/${workflowId}/schedules/${scheduleId}`);
  }
}
