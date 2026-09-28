import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { JobDetail as Job, POLL_INTERVAL_MS } from './execution.service';
import { JobDetail } from './job-detail';

function job(overrides: Partial<Job>): Job {
  return {
    id: 301,
    executionId: 91,
    stepKey: 'create_crm_contact',
    stepName: 'Create CRM contact',
    jobType: 'HTTP',
    status: 'SUCCEEDED',
    config: { method: 'POST', url: 'https://crm.example.com/api/contacts' },
    timeoutSeconds: 30,
    maxAttempts: 3,
    attemptCount: 1,
    dependsOn: [],
    availableAt: null,
    startedAt: '2026-09-28T10:00:00Z',
    finishedAt: '2026-09-28T10:00:01Z',
    output: { status: 201, body: { id: 11 } },
    lastError: null,
    attempts: [
      {
        number: 1,
        status: 'SUCCEEDED',
        workerId: 'host:1234',
        startedAt: '2026-09-28T10:00:00Z',
        finishedAt: '2026-09-28T10:00:01Z',
        errorType: null,
        errorMessage: null,
        retryable: null,
      },
    ],
    ...overrides,
  };
}

describe('JobDetail', () => {
  let fixture: ComponentFixture<JobDetail>;
  let element: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [JobDetail],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { paramMap: of(convertToParamMap({ id: '301' })) },
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(JobDetail);
    element = fixture.nativeElement;
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('shows the output and every attempt of a finished job', async () => {
    http.expectOne('/api/job-executions/301').flush(job({}));
    fixture.detectChanges();

    expect(element.querySelector('.output')?.textContent).toContain('"id": 11');
    expect(element.querySelectorAll('.attempts tbody tr')).toHaveLength(1);
    expect(element.textContent).toContain('attempt 1 of 3');

    vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);
    http.expectNone('/api/job-executions/301');
  });

  it('shows why a job failed', () => {
    http.expectOne('/api/job-executions/301').flush(
      job({
        status: 'FAILED',
        output: null,
        lastError: 'HTTP 503 Service Unavailable',
        attempts: [
          {
            number: 1,
            status: 'FAILED',
            workerId: 'host:1234',
            startedAt: '2026-09-28T10:00:00Z',
            finishedAt: '2026-09-28T10:00:01Z',
            errorType: 'HTTP_5XX',
            errorMessage: 'HTTP 503 Service Unavailable',
            retryable: null,
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(element.querySelector('.last-error')?.textContent).toContain('HTTP 503');
    expect(element.querySelector('.attempts')?.textContent).toContain('HTTP_5XX');
    expect(element.textContent).toContain('No output yet.');
  });

  it('keeps refreshing while the job is still waiting or running', () => {
    http.expectOne('/api/job-executions/301').flush(job({ status: 'RUNNING', output: null }));
    vi.advanceTimersByTime(POLL_INTERVAL_MS);
    http.expectOne('/api/job-executions/301').flush(job({}));
    fixture.detectChanges();

    expect(element.querySelector('app-status-badge')?.textContent).toContain('SUCCEEDED');
    vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);
    http.expectNone('/api/job-executions/301');
  });
});
