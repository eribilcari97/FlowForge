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
