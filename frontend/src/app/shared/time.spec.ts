import { elapsedMs, formatDuration, plural, relativeTime } from './time';

describe('time helpers', () => {
  const now = Date.parse('2026-09-28T10:00:00Z');

  it('measures elapsed time up to now while unfinished', () => {
    expect(elapsedMs('2026-09-28T09:59:30Z', null, now)).toBe(30_000);
    expect(elapsedMs('2026-09-28T09:00:00Z', '2026-09-28T09:00:01.500Z', now)).toBe(1500);
    expect(elapsedMs(null, null, now)).toBeNull();
  });

  it('formats durations at a readable precision', () => {
    expect(formatDuration(184)).toBe('184 ms');
    expect(formatDuration(12_400)).toBe('12 s');
    expect(formatDuration(72_000)).toBe('1m 12s');
    expect(formatDuration(2 * 3_600_000 + 5 * 60_000)).toBe('2h 05m');
    expect(formatDuration(28 * 3_600_000)).toBe('1d 4h');
  });

  it('describes past and future times relative to now', () => {
    expect(relativeTime('2026-09-28T09:59:40Z', now)).toBe('just now');
    expect(relativeTime('2026-09-28T09:55:00Z', now)).toBe('5 min ago');
    expect(relativeTime('2026-09-28T07:00:00Z', now)).toBe('3 h ago');
    expect(relativeTime('2026-09-26T09:00:00Z', now)).toBe('2 days ago');
    expect(relativeTime('2026-09-28T13:00:00Z', now)).toBe('in 3 h');
  });

  it('pluralises nouns', () => {
    expect(plural(1, 'run')).toBe('1 run');
    expect(plural(3, 'run')).toBe('3 runs');
  });
});
