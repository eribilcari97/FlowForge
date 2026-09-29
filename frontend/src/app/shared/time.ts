const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function elapsedMs(
  startIso: string | null,
  endIso: string | null,
  nowMs: number,
): number | null {
  if (!startIso) {
    return null;
  }
  const end = endIso ? Date.parse(endIso) : nowMs;
  return Math.max(0, end - Date.parse(startIso));
}

export function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
  }
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

export function relativeTime(iso: string, nowMs: number): string {
  const diff = Date.parse(iso) - nowMs;
  const distance = Math.abs(diff);
  if (distance < MINUTE) {
    return diff < 0 ? 'just now' : 'in less than a minute';
  }
  const amount =
    distance < HOUR
      ? `${Math.floor(distance / MINUTE)} min`
      : distance < DAY
        ? `${Math.floor(distance / HOUR)} h`
        : plural(Math.floor(distance / DAY), 'day');
  return diff < 0 ? `${amount} ago` : `in ${amount}`;
}

export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}
