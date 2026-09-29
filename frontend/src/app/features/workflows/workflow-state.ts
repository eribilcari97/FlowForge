import { plural, relativeTime } from '../../shared/time';
import { ExecutionSummary } from '../executions/execution.service';
import { describeCron } from '../schedules/cron-description';
import { Schedule } from '../schedules/schedule.service';
import { WorkflowStatus } from './workflow.service';

export interface TriggerSummary {
  label: string;
  detail: string | null;
}

export function triggerSummary(
  status: WorkflowStatus,
  schedules: Schedule[],
  nowMs: number,
): TriggerSummary {
  const enabled = schedules.filter((schedule) => schedule.enabled);
  if (schedules.length === 0) {
    return { label: 'Triggered manually', detail: null };
  }
  if (enabled.length === 0) {
    return { label: 'Triggered manually, schedule paused', detail: null };
  }
  const label =
    enabled.length === 1
      ? (describeCron(enabled[0].cronExpression) ?? 'Runs on a custom schedule')
      : `Runs on ${enabled.length} schedules`;
  if (status !== 'ACTIVE') {
    return { label, detail: 'Starts once the workflow is active' };
  }
  const next = enabled
    .map((schedule) => schedule.nextRunAt)
    .filter((at): at is string => at !== null)
    .sort()[0];
  return { label, detail: next ? `Next run ${relativeTime(next, nowMs)}` : null };
}

export interface RunSummary {
  total: string;
  failures: string;
}

export function runSummary(recentRuns: ExecutionSummary[], totalRuns: number): RunSummary {
  const failed = recentRuns.filter((run) => run.status === 'FAILED').length;
  const complete = totalRuns <= recentRuns.length;
  const count = failed === 0 ? 'none failed' : `${failed} failed`;
  return {
    total: plural(totalRuns, 'run').replace(/^\d+/, (n) => Number(n).toLocaleString('en-US')),
    failures: complete ? count : `${count} of the last ${recentRuns.length}`,
  };
}

export const LAST_RUN_LABELS: Record<ExecutionSummary['status'], string> = {
  SUCCEEDED: 'Completed',
  FAILED: 'Failed',
  RUNNING: 'Running now',
  CANCELLED: 'Cancelled',
};
