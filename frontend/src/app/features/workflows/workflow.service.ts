import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
export type JobType = 'HTTP' | 'DELAY' | 'TRANSFORM' | 'EMAIL';
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface HttpStepConfig {
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: unknown;
  expectedStatus?: number[];
}

export interface DelayStepConfig {
  duration: string;
}

export interface TransformStepConfig {
  expression: string;
}

export interface EmailStepConfig {
  to: string[];
  cc?: string[];
  subject: string;
  text?: string;
  html?: string;
}

export type StepConfig = HttpStepConfig | DelayStepConfig | TransformStepConfig | EmailStepConfig;

export interface Step {
  id: number;
  key: string;
  name: string;
  jobType: JobType;
  config: StepConfig;
  timeoutSeconds: number;
  maxAttempts: number;
  retryDelaySeconds: number;
  dependsOn: number[];
}

export interface StepDependencies {
  stepId: number;
  dependsOn: number[];
}

export interface WorkflowSummary {
  id: number;
  projectId: number;
  name: string;
  description: string | null;
  status: WorkflowStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Workflow extends WorkflowSummary {
  version: number;
  steps: Step[];
}

export interface WorkflowRequest {
  name: string;
  description: string | null;
}

export interface WorkflowUpdateRequest extends WorkflowRequest {
  version: number;
}

export interface StepUpdateRequest {
  name: string;
  config: StepConfig;
  timeoutSeconds: number;
  maxAttempts: number;
  retryDelaySeconds: number;
}

export interface StepCreateRequest extends StepUpdateRequest {
  key: string;
  jobType: JobType;
}

@Injectable({ providedIn: 'root' })
export class WorkflowService {
  private readonly http = inject(HttpClient);

  list(projectId: number): Observable<WorkflowSummary[]> {
    return this.http.get<WorkflowSummary[]>(`/api/projects/${projectId}/workflows`);
  }

  get(id: number): Observable<Workflow> {
    return this.http.get<Workflow>(`/api/workflows/${id}`);
  }

  create(projectId: number, request: WorkflowRequest): Observable<Workflow> {
    return this.http.post<Workflow>(`/api/projects/${projectId}/workflows`, request);
  }

  update(id: number, request: WorkflowUpdateRequest): Observable<Workflow> {
    return this.http.put<Workflow>(`/api/workflows/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/workflows/${id}`);
  }

  activate(id: number): Observable<Workflow> {
    return this.http.post<Workflow>(`/api/workflows/${id}/activate`, null);
  }

  deactivate(id: number): Observable<Workflow> {
    return this.http.post<Workflow>(`/api/workflows/${id}/deactivate`, null);
  }

  setDependencies(
    workflowId: number,
    stepId: number,
    dependsOn: number[],
  ): Observable<StepDependencies> {
    return this.http.put<StepDependencies>(
      `/api/workflows/${workflowId}/steps/${stepId}/dependencies`,
      { dependsOn },
    );
  }

  addStep(workflowId: number, request: StepCreateRequest): Observable<Step> {
    return this.http.post<Step>(`/api/workflows/${workflowId}/steps`, request);
  }

  updateStep(workflowId: number, stepId: number, request: StepUpdateRequest): Observable<Step> {
    return this.http.put<Step>(`/api/workflows/${workflowId}/steps/${stepId}`, request);
  }

  deleteStep(workflowId: number, stepId: number): Observable<void> {
    return this.http.delete<void>(`/api/workflows/${workflowId}/steps/${stepId}`);
  }
}
