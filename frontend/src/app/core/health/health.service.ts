import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

/** `UP`/`DOWN` as reported by Spring Boot Actuator, or `UNREACHABLE` when no answer came back. */
export type BackendStatus = 'UP' | 'DOWN' | 'UNREACHABLE';

interface HealthResponse {
  status: string;
}

@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  /** Reads `/actuator/health`, which the dev proxy forwards to the backend. */
  status(): Observable<BackendStatus> {
    return this.http.get<HealthResponse>('/actuator/health').pipe(
      map((health) => toStatus(health.status)),
      // Actuator answers 503 with a body of { "status": "DOWN" } when a component (e.g. the database) is down.
      catchError((error: HttpErrorResponse) => of(toStatus(error.error?.status))),
    );
  }
}

function toStatus(value: unknown): BackendStatus {
  if (value === 'UP') {
    return 'UP';
  }
  return value === undefined || value === null ? 'UNREACHABLE' : 'DOWN';
}
