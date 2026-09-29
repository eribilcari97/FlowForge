import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, of, switchMap } from 'rxjs';

import { ExecutionService, ExecutionSummary } from '../executions/execution.service';
import { Project, ProjectService } from '../projects/project.service';
import { Schedule, ScheduleService } from '../schedules/schedule.service';
import { WorkflowService, WorkflowSummary } from './workflow.service';

export const RECENT_RUNS = 20;

export interface WorkflowOverview {
  workflow: WorkflowSummary;
  project: Project;
  recentRuns: ExecutionSummary[];
  totalRuns: number;
  schedules: Schedule[];
}

export interface Workspace {
  projects: Project[];
  workflows: WorkflowOverview[];
}

@Injectable({ providedIn: 'root' })
export class WorkflowOverviewService {
  private readonly projects = inject(ProjectService);
  private readonly workflows = inject(WorkflowService);
  private readonly executions = inject(ExecutionService);
  private readonly schedules = inject(ScheduleService);

  load(): Observable<Workspace> {
    return this.projects
      .list()
      .pipe(
        switchMap((projects) =>
          this.forProjects(projects).pipe(map((workflows) => ({ projects, workflows }))),
        ),
      );
  }

  forProjects(projects: Project[]): Observable<WorkflowOverview[]> {
    if (projects.length === 0) {
      return of([]);
    }
    return forkJoin(
      projects.map((project) =>
        this.workflows
          .list(project.id)
          .pipe(map((workflows) => workflows.map((workflow) => ({ workflow, project })))),
      ),
    ).pipe(
      map((groups) => groups.flat()),
      switchMap((pairs) =>
        pairs.length === 0
          ? of([])
          : forkJoin(pairs.map(({ workflow, project }) => this.overview(workflow, project))),
      ),
    );
  }

  private overview(workflow: WorkflowSummary, project: Project): Observable<WorkflowOverview> {
    return forkJoin({
      runs: this.executions.listOfWorkflow(workflow.id, 0, RECENT_RUNS),
      schedules: this.schedules.list(workflow.id),
    }).pipe(
      map(({ runs, schedules }) => ({
        workflow,
        project,
        recentRuns: runs.items,
        totalRuns: runs.total,
        schedules,
      })),
    );
  }
}
