import { describeCron } from './cron-description';

describe('describeCron', () => {
  it.each([
    ['* * * * *', 'Runs every minute'],
    ['*/15 * * * *', 'Runs every 15 minutes'],
    ['0 * * * *', 'Runs every hour'],
    ['30 * * * *', 'Runs every hour at :30'],
    ['0 */6 * * *', 'Runs every 6 hours'],
    ['0 8 * * *', 'Runs every day at 08:00'],
    ['30 2 * * *', 'Runs every day at 02:30'],
    ['0 8 * * 1-5', 'Runs on weekdays at 08:00'],
    ['0 9 * * 1', 'Runs every Monday at 09:00'],
    ['0 9 * * 1,5', 'Runs on Monday and Friday at 09:00'],
    ['0 9 * * 0,3,6', 'Runs on Sunday, Wednesday and Saturday at 09:00'],
    ['0 6 1 * *', 'Runs on day 1 of every month at 06:00'],
  ])('describes %s', (cron, description) => {
    expect(describeCron(cron)).toBe(description);
  });

  it.each(['0 6,18 * * *', '0 8 * 1 *', '0 8 1 * 1', 'not a cron'])(
    'leaves %s to the caller',
    (cron) => {
      expect(describeCron(cron)).toBeNull();
    },
  );
});
