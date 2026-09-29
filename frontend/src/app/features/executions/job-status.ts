import { JobStatus } from './execution.service';

export interface RetryState {
  status: JobStatus;
  attemptCount: number;
  maxAttempts: number;
  availableAt: string | null;
}

export function retryCountdown(job: RetryState, nowMs: number): string | null {
  if (job.status !== 'READY' || job.attemptCount === 0 || !job.availableAt) {
    return null;
  }
  const nextAttempt = `retry ${job.attemptCount + 1}/${job.maxAttempts}`;
  const secondsLeft = Math.ceil((Date.parse(job.availableAt) - nowMs) / 1000);
  return secondsLeft > 0 ? `${nextAttempt} in ${secondsLeft} s` : `${nextAttempt} starting…`;
}

export function retryableLabel(retryable: boolean | null): string {
  if (retryable === null) {
    return '—';
  }
  return retryable ? 'yes' : 'no';
}

export interface TimelineBar {
  left: number;
  width: number;
}

export function timelineBar(
  run: { createdAt: string; finishedAt: string | null },
  job: { startedAt: string | null; finishedAt: string | null },
  nowMs: number,
): TimelineBar | null {
  if (!job.startedAt) {
    return null;
  }
  const start = Date.parse(run.createdAt);
  const end = run.finishedAt ? Date.parse(run.finishedAt) : nowMs;
  const span = Math.max(end - start, 1);
  const jobStart = Date.parse(job.startedAt);
  const jobEnd = job.finishedAt ? Date.parse(job.finishedAt) : end;
  const left = Math.min(Math.max(((jobStart - start) / span) * 100, 0), 100);
  const width = Math.min(Math.max(((jobEnd - jobStart) / span) * 100, 0), 100 - left);
  return { left, width: Math.max(width, 0.75) };
}
