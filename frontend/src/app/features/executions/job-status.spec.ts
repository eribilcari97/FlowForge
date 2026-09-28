import { retryCountdown, retryableLabel } from './job-status';

const now = Date.parse('2026-09-28T10:00:00Z');

describe('retryCountdown', () => {
  it('counts down to the next attempt of a job waiting to retry', () => {
    expect(
      retryCountdown(
        { status: 'READY', attemptCount: 1, maxAttempts: 3, availableAt: '2026-09-28T10:00:08Z' },
        now,
      ),
    ).toBe('retry 2/3 in 8 s');
  });

  it('rounds partial seconds up', () => {
    expect(
      retryCountdown(
        {
          status: 'READY',
          attemptCount: 2,
          maxAttempts: 3,
          availableAt: '2026-09-28T10:00:00.300Z',
        },
        now,
      ),
    ).toBe('retry 3/3 in 1 s');
  });

  it('says the retry is starting once its time has come', () => {
    expect(
      retryCountdown(
        { status: 'READY', attemptCount: 1, maxAttempts: 3, availableAt: '2026-09-28T09:59:59Z' },
        now,
      ),
    ).toBe('retry 2/3 starting…');
  });

  it('shows nothing for jobs that are not waiting to retry', () => {
    const base = { attemptCount: 1, maxAttempts: 3, availableAt: '2026-09-28T10:00:08Z' };

    expect(retryCountdown({ ...base, status: 'READY', attemptCount: 0 }, now)).toBeNull();
    expect(retryCountdown({ ...base, status: 'RUNNING' }, now)).toBeNull();
    expect(retryCountdown({ ...base, status: 'FAILED' }, now)).toBeNull();
    expect(retryCountdown({ ...base, status: 'READY', availableAt: null }, now)).toBeNull();
  });
});

describe('retryableLabel', () => {
  it('describes whether a failed attempt could be retried', () => {
    expect(retryableLabel(true)).toBe('yes');
    expect(retryableLabel(false)).toBe('no');
    expect(retryableLabel(null)).toBe('—');
  });
});
