import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { WorkflowStatus } from '../workflows/workflow.service';
import { ScheduleList } from './schedule-list';
import { Schedule } from './schedule.service';

const SCHEDULE: Schedule = {
  id: 3,
  workflowId: 5,
  cronExpression: '0 8 * * *',
  timezone: 'Europe/Berlin',
  input: { report: 'daily' },
  enabled: true,
  nextRunAt: '2026-12-02T07:00:00Z',
  lastRunAt: null,
};

describe('ScheduleList', () => {
  let fixture: ComponentFixture<ScheduleList>;
  let element: HTMLElement;
  let http: HttpTestingController;

  async function setUp(status: WorkflowStatus, schedules: Schedule[]) {
    TestBed.configureTestingModule({
      imports: [ScheduleList],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ScheduleList);
    fixture.componentRef.setInput('workflowId', 5);
    fixture.componentRef.setInput('workflowStatus', status);
    element = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne('/api/workflows/5/schedules').flush(schedules);
    await fixture.whenStable();
  }

  function form() {
    return fixture.componentInstance['form'];
  }

  afterEach(() => http.verify());

  it('shows the next run in the time zone of the schedule', async () => {
    await setUp('ACTIVE', [SCHEDULE]);

    const row = element.querySelector('.schedules tbody tr')!.textContent!;
    expect(row).toContain('0 8 * * *');
    expect(row).toContain('Every day at 08:00');
    expect(element.querySelector('.next-run')?.textContent).toContain('08:00');
    expect(element.querySelector('.schedule-note')).toBeNull();
  });

  it('explains that schedules of an inactive workflow do not fire', async () => {
    await setUp('DRAFT', []);

    expect(element.querySelector('.schedule-note')?.textContent).toContain(
      'only fire while the workflow is active',
    );
  });

  it('creates a schedule from a preset', async () => {
    await setUp('ACTIVE', []);
    fixture.componentInstance.applyPreset('0 8 * * 1-5');
    form().patchValue({ timezone: 'Europe/Berlin', input: '{"report": "weekly"}' });

    fixture.componentInstance.save();

    const request = http.expectOne({ method: 'POST', url: '/api/workflows/5/schedules' });
    expect(request.request.body).toEqual({
      cronExpression: '0 8 * * 1-5',
      timezone: 'Europe/Berlin',
      input: { report: 'weekly' },
      enabled: true,
    });
    request.flush(
      { ...SCHEDULE, cronExpression: '0 8 * * 1-5' },
      { status: 201, statusText: 'Created' },
    );
    http.expectOne('/api/workflows/5/schedules').flush([]);
  });

  it('shows the server message next to an invalid field', async () => {
    await setUp('ACTIVE', []);
    form().patchValue({ cronExpression: '0 0 8 * * *', timezone: 'Berlin' });

    fixture.componentInstance.save();
    http.expectOne({ method: 'POST', url: '/api/workflows/5/schedules' }).flush(
      {
        status: 400,
        code: 'VALIDATION_ERROR',
        errors: [
          { field: 'cronExpression', message: 'must be a valid cron expression with 5 fields' },
          { field: 'timezone', message: 'must be an IANA time zone such as Europe/Berlin' },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    expect(form().controls.cronExpression.getError('server')).toContain('5 fields');
    expect(element.textContent).toContain('must be an IANA time zone');
  });

  it('pauses a schedule by saving it as disabled', async () => {
    await setUp('ACTIVE', [SCHEDULE]);

    (element.querySelector('button.toggle') as HTMLButtonElement).click();

    const request = http.expectOne({ method: 'PUT', url: '/api/workflows/5/schedules/3' });
    expect(request.request.body.enabled).toBe(false);
    request.flush({ ...SCHEDULE, enabled: false, nextRunAt: null });
    await fixture.whenStable();

    expect(element.querySelector('.next-run')?.textContent).toContain('—');
    expect(element.querySelector('button.toggle')?.textContent).toContain('Resume');
  });

  it('is read-only for an archived workflow', async () => {
    await setUp('ARCHIVED', [SCHEDULE]);

    expect(element.querySelector('.schedule-form')).toBeNull();
    expect(element.querySelector('button.toggle')).toBeNull();
  });
});
