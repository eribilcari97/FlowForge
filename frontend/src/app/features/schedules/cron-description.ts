const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

export function describeCron(cron: string): string | null {
  const fields = cron.trim().split(/\s+/);
  if (fields.length !== 5) {
    return null;
  }
  const [minute, hour, day, month, weekday] = fields;
  if (month !== '*') {
    return null;
  }
  const everyDay = day === '*' && weekday === '*';

  if (hour === '*' && everyDay) {
    if (minute === '*') {
      return 'Runs every minute';
    }
    const step = stepOf(minute);
    if (step) {
      return `Runs every ${step} minutes`;
    }
    if (isNumber(minute)) {
      return minute === '0' ? 'Runs every hour' : `Runs every hour at :${pad(minute)}`;
    }
    return null;
  }

  if (!isNumber(minute)) {
    return null;
  }
  const hourStep = stepOf(hour);
  if (hourStep && everyDay) {
    return `Runs every ${hourStep} hours`;
  }
  if (!isNumber(hour)) {
    return null;
  }
  const time = `${pad(hour)}:${pad(minute)}`;

  if (everyDay) {
    return `Runs every day at ${time}`;
  }
  if (day === '*') {
    if (weekday === '1-5') {
      return `Runs on weekdays at ${time}`;
    }
    const names = weekdayNames(weekday);
    if (!names) {
      return null;
    }
    return names.length === 1
      ? `Runs every ${names[0]} at ${time}`
      : `Runs on ${names.slice(0, -1).join(', ')} and ${names.at(-1)} at ${time}`;
  }
  if (weekday === '*' && isNumber(day)) {
    return `Runs on day ${day} of every month at ${time}`;
  }
  return null;
}

function isNumber(field: string): boolean {
  return /^\d+$/.test(field);
}

function stepOf(field: string): number | null {
  const match = /^\*\/(\d+)$/.exec(field);
  return match ? Number(match[1]) : null;
}

function pad(field: string): string {
  return field.padStart(2, '0');
}

function weekdayNames(field: string): string[] | null {
  const parts = field.split(',');
  if (!parts.every((part) => /^[0-7]$/.test(part))) {
    return null;
  }
  return parts.map((part) => DAYS[Number(part) % 7]);
}
