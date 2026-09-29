import { ExecutionSummary } from '../executions/execution.service';
import { Schedule } from '../schedules/schedule.service';
import { runSummary, triggerSummary } from './workflow-state';

function schedule(overrides: Partial<Schedule>): Schedule {
  return {
    id: 1,
    workflowId: 5,
    cronExpression: '0 8 * * *',
    timezone: 'Europe/Berlin',
    input: {},
    enabled: true,
    nextRunAt: '2026-09-28T13:00:00Z',
    lastRunAt: null,
    ...overrides,
  };
}

function run(id: number, status: ExecutionSummary['status']): ExecutionSummary {
  return {
    id,
    workflowId: 5,
    workflowName: 'Sync',
    runNumber: id,
    status,
    triggerType: 'SCHEDULE',
    createdAt: '2026-09-28T09:00:00Z',
    finishedAt: '2026-09-28T09:00:05Z',
    errorSummary: null,
  };
}

describe('triggerSummary', () => {
  const now = Date.parse('2026-09-28T10:00:00Z');

  it('treats a workflow without schedules as manual', () => {
    expect(triggerSummary('ACTIVE', [], now)).toEqual({
      label: 'Triggered manually',
      detail: null,
    });
  });

  it('describes the schedule in plain words and when it fires next', () => {
    expect(triggerSummary('ACTIVE', [schedule({})], now)).toEqual({
      label: 'Runs every day at 08:00',
      detail: 'Next run in 3 h',
    });
  });

  it('counts several enabled schedules and uses the soonest next run', () => {
    const summary = triggerSummary(
      'ACTIVE',
      [
        schedule({ cronExpression: '0 6,18 * * *', nextRunAt: '2026-09-29T06:00:00Z' }),
        schedule({ id: 2, nextRunAt: '2026-09-28T10:30:00Z' }),
      ],
      now,
    );

    expect(summary).toEqual({ label: 'Runs on 2 schedules', detail: 'Next run in 30 min' });
  });

  it('falls back to a generic label for schedules it cannot put into words', () => {
    expect(
      triggerSummary('ACTIVE', [schedule({ cronExpression: '0 6,18 * * *' })], now).label,
    ).toBe('Runs on a custom schedule');
  });

  it('explains that schedules of an inactive workflow do not fire', () => {
    expect(triggerSummary('DRAFT', [schedule({})], now).detail).toBe(
      'Starts once the workflow is active',
    );
  });

  it('treats paused schedules as manual triggering', () => {
    expect(triggerSummary('ACTIVE', [schedule({ enabled: false, nextRunAt: null })], now)).toEqual({
      label: 'Triggered manually, schedule paused',
      detail: null,
    });
  });
});

describe('runSummary', () => {
  it('counts failures exactly when every run is known', () => {
    expect(runSummary([run(3, 'FAILED'), run(2, 'SUCCEEDED'), run(1, 'FAILED')], 3)).toEqual({
      total: '3 runs',
      failures: '2 failed',
    });
  });

  it('says which runs the failure count covers when older runs are not loaded', () => {
    expect(runSummary([run(1284, 'SUCCEEDED'), run(1283, 'FAILED')], 1284)).toEqual({
      total: '1,284 runs',
      failures: '1 failed of the last 2',
    });
  });

  it('reports a clean history', () => {
    expect(runSummary([run(1, 'SUCCEEDED')], 1)).toEqual({
      total: '1 run',
      failures: 'none failed',
    });
  });
});
