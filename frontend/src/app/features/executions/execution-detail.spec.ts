import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ExecutionDetail } from './execution-detail';
import { Execution, JobSummary } from './execution.service';

function job(overrides: Partial<JobSummary>): JobSummary {
  return {
    id: 1,
    stepKey: 'step',
    jobType: 'HTTP',
    status: 'SUCCEEDED',
    attemptCount: 1,
    maxAttempts: 3,
    availableAt: null,
    lastError: null,
    dependsOn: [],
    startedAt: null,
    finishedAt: null,
    ...overrides,
  };
}

describe('ExecutionDetail', () => {
  let fixture: ComponentFixture<ExecutionDetail>;
  let element: HTMLElement;
  let http: HttpTestingController;
  const now = Date.parse('2026-09-28T10:00:00Z');

  const execution: Execution = {
    id: 91,
    workflowId: 5,
    workflowName: 'Customer onboarding',
    runNumber: 4,
    status: 'RUNNING',
    triggerType: 'MANUAL',
    createdAt: '2026-09-28T09:59:00Z',
    finishedAt: null,
    errorSummary: null,
    input: {},
    jobs: [
      job({ id: 1, stepKey: 'fetch', status: 'SUCCEEDED' }),
      job({
        id: 2,
        stepKey: 'flaky',
        status: 'READY',
        attemptCount: 1,
        availableAt: '2026-09-28T10:00:08Z',
        lastError: 'HTTP 503 Service Unavailable',
      }),
      job({ id: 3, stepKey: 'broken', status: 'FAILED', lastError: 'HTTP 400 Bad Request' }),
      job({ id: 4, stepKey: 'after_broken', status: 'SKIPPED' }),
    ],
  };

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
    TestBed.configureTestingModule({
      imports: [ExecutionDetail],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { paramMap: of(convertToParamMap({ id: '91' })) },
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ExecutionDetail);
    element = fixture.nativeElement;
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
    http.expectOne('/api/executions/91').flush(execution);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.match('/api/executions/91');
    vi.useRealTimers();
  });

  function stepKeys(): string[] {
    return [...element.querySelectorAll('.jobs tbody code')].map((code) => code.textContent!);
  }

  it('counts down to the next attempt of a job waiting to retry', () => {
    expect(element.querySelector('.retry-countdown')?.textContent).toContain('retry 2/3 in 8 s');

    vi.advanceTimersByTime(3000);
    fixture.detectChanges();

    expect(element.querySelector('.retry-countdown')?.textContent).toContain('retry 2/3 in 5 s');
  });

  it('can show only the failed jobs', () => {
    expect(stepKeys()).toEqual(['fetch', 'flaky', 'broken', 'after_broken']);
    expect(element.querySelector('.failed-filter')?.textContent).toContain('Failed (1)');

    fixture.componentInstance['onlyFailed'].set(true);
    fixture.detectChanges();

    expect(stepKeys()).toEqual(['broken']);
  });
});
