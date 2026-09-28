import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { Execution, ExecutionService, POLL_INTERVAL_MS } from './execution.service';

function execution(status: Execution['status']): Execution {
  return {
    id: 91,
    workflowId: 5,
    workflowName: 'Customer onboarding',
    runNumber: 1,
    status,
    triggerType: 'MANUAL',
    createdAt: '2026-09-28T10:00:00Z',
    finishedAt: status === 'RUNNING' ? null : '2026-09-28T10:01:00Z',
    errorSummary: null,
    input: {},
    jobs: [],
  };
}

describe('ExecutionService', () => {
  let service: ExecutionService;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ExecutionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('polls every 2 seconds while the execution is running and stops once it has finished', () => {
    const received: string[] = [];
    let completed = false;
    service.watch(91).subscribe({
      next: (e) => received.push(e.status),
      complete: () => (completed = true),
    });

    vi.advanceTimersByTime(0);
    http.expectOne('/api/executions/91').flush(execution('RUNNING'));
    vi.advanceTimersByTime(POLL_INTERVAL_MS - 1);
    http.expectNone('/api/executions/91');

    vi.advanceTimersByTime(1);
    http.expectOne('/api/executions/91').flush(execution('RUNNING'));
    vi.advanceTimersByTime(POLL_INTERVAL_MS);
    http.expectOne('/api/executions/91').flush(execution('CANCELLED'));

    expect(received).toEqual(['RUNNING', 'RUNNING', 'CANCELLED']);
    expect(completed).toBe(true);
    vi.advanceTimersByTime(POLL_INTERVAL_MS * 5);
    http.expectNone('/api/executions/91');
  });

  it('does not poll a finished execution again', () => {
    let completed = false;
    service.watch(91).subscribe({ complete: () => (completed = true) });

    vi.advanceTimersByTime(0);
    http.expectOne('/api/executions/91').flush(execution('SUCCEEDED'));

    expect(completed).toBe(true);
    vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);
    http.expectNone('/api/executions/91');
  });

  it('sends the idempotency key and the input when starting a run', () => {
    service.start(5, { customer: { plan: 'PRO' } }, 'key-1').subscribe();

    const request = http.expectOne({ method: 'POST', url: '/api/workflows/5/executions' });
    expect(request.request.headers.get('Idempotency-Key')).toBe('key-1');
    expect(request.request.body).toEqual({ input: { customer: { plan: 'PRO' } } });
    request.flush(execution('RUNNING'), { status: 202, statusText: 'Accepted' });
  });

  it('asks for one status and page of all executions', () => {
    service.list('FAILED', 2, 20).subscribe();

    const request = http.expectOne((r) => r.url === '/api/executions');
    expect(request.request.params.get('status')).toBe('FAILED');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ items: [], page: 2, size: 20, total: 0 });
  });
});
