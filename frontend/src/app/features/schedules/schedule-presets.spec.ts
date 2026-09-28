import { CRON_PRESETS, formatInZone, presetLabel } from './schedule-presets';

describe('schedule presets', () => {
  it('names a cron expression that matches a preset', () => {
    expect(presetLabel('0 8 * * 1-5')).toBe('Weekdays at 08:00');
    expect(presetLabel(' */5 * * * * ')).toBe('Every 5 minutes');
    expect(presetLabel('17 3 * * *')).toBeNull();
  });

  it('offers only 5-field expressions', () => {
    for (const preset of CRON_PRESETS) {
      expect(preset.cron.split(' ')).toHaveLength(5);
    }
  });

  it('shows a time in the time zone of the schedule', () => {
    expect(formatInZone('2026-07-02T06:00:00Z', 'Europe/Berlin')).toContain('08:00');
    expect(formatInZone('2026-12-02T07:00:00Z', 'Europe/Berlin')).toContain('08:00');
    expect(formatInZone('2026-12-02T07:00:00Z', 'UTC')).toContain('07:00');
  });
});
