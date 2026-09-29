import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ExecutionSummary } from '../executions/execution.service';
import { Schedule } from '../schedules/schedule.service';
import { WorkflowCard } from './workflow-card';
import { WorkflowOverview } from './workflow-overview.service';

const NOW = Date.parse('2026-09-28T10:00:00Z');

function run(id: number, status: ExecutionSummary['status'], createdAt: string): ExecutionSummary {
  return {
    id,
    workflowId: 5,
    workflowName: 'Customer data sync',
    runNumber: id,
    status,
    triggerType: 'SCHEDULE',
    createdAt,
    finishedAt: status === 'RUNNING' ? null : createdAt,
    errorSummary: null,
  };
}

const SCHEDULE: Schedule = {
  id: 1,
  workflowId: 5,
  cronExpression: '*/15 * * * *',
  timezone: 'UTC',
  input: {},
  enabled: true,
  nextRunAt: '2026-09-28T10:15:00Z',
  lastRunAt: '2026-09-28T09:58:00Z',
};

function overview(overrides: Partial<WorkflowOverview> = {}): WorkflowOverview {
  return {
    workflow: {
      id: 5,
      projectId: 7,
      name: 'Customer data sync',
      description: 'Keeps customer records synchronized with the CRM.',
      status: 'ACTIVE',
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
    },
    project: { id: 7, name: 'Ops', description: null, workflowCount: 1, createdAt: '' },
    recentRuns: [
      run(3, 'SUCCEEDED', '2026-09-28T09:58:00Z'),
      run(2, 'FAILED', '2026-09-28T09:43:00Z'),
      run(1, 'SUCCEEDED', '2026-09-28T09:28:00Z'),
    ],
    totalRuns: 3,
    schedules: [SCHEDULE],
    ...overrides,
  };
}

function render(value: WorkflowOverview): HTMLElement {
  TestBed.configureTestingModule({ imports: [WorkflowCard], providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(WorkflowCard);
  fixture.componentRef.setInput('overview', value);
  fixture.componentRef.setInput('now', NOW);
  fixture.detectChanges();
  return fixture.nativeElement;
}

function lines(value: WorkflowOverview): string[] {
  return [...render(value).querySelectorAll('h3, p, a')].map((element) =>
    element.textContent!.replace(/\s+/g, ' ').trim(),
  );
}

describe('WorkflowCard', () => {
  it('answers what it is, whether it runs and whether it worked', () => {
    expect(lines(overview())).toEqual([
      'Customer data sync',
      'Keeps customer records synchronized with the CRM.',
      'Active',
      'Runs every 15 minutes',
      'Last run: 2 min ago · Completed',
      '3 runs · 1 failed',
      'View workflow: Customer data sync→',
    ]);
  });

  it('says which runs the failure count is based on when history is longer', () => {
    expect(lines(overview({ totalRuns: 1284 }))).toContain('1,284 runs · 1 failed of the last 3');
  });

  it('shows an inactive workflow that has never run', () => {
    expect(
      lines(
        overview({
          workflow: { ...overview().workflow, status: 'DRAFT', description: null },
          recentRuns: [],
          totalRuns: 0,
        }),
      ),
    ).toEqual([
      'Customer data sync',
      'No description yet.',
      'Inactive',
      'Runs every 15 minutes, once active',
      'Not run yet',
      'View workflow: Customer data sync→',
    ]);
  });

  it('links the whole card to the workflow', () => {
    const link = render(overview()).querySelector('a.view');

    expect(link?.getAttribute('href')).toBe('/workflows/5');
  });
});
