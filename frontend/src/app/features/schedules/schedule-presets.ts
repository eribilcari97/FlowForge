export interface CronPreset {
  label: string;
  cron: string;
}

export const CRON_PRESETS: CronPreset[] = [
  { label: 'Every 5 minutes', cron: '*/5 * * * *' },
  { label: 'Every hour', cron: '0 * * * *' },
  { label: 'Every day at 08:00', cron: '0 8 * * *' },
  { label: 'Weekdays at 08:00', cron: '0 8 * * 1-5' },
  { label: 'Mondays at 09:00', cron: '0 9 * * 1' },
];

export function presetLabel(cron: string): string | null {
  return CRON_PRESETS.find((preset) => preset.cron === cron.trim())?.label ?? null;
}

export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

export function formatInZone(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone,
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}
