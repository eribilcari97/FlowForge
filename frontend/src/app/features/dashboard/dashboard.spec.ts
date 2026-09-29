import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Dashboard } from './dashboard';
import { DASHBOARD_REFRESH_MS, Dashboard as DashboardData } from './dashboard.service';

const DATA: DashboardData = {
  running: {
    items: [
      {
        id: 91,
        workflowId: 5,
        workflowName: 'Customer onboarding',
        runNumber: 12,
        status: 'RUNNING',
        triggerType: 'MANUAL',
        createdAt: '2026-09-28T10:00:00Z',
        finishedAt: null,
        errorSummary: null,
      },
    ],
    page: 0,
    size: 10,
    total: 1,
  },
  failedLast24Hours: {
    items: [
      {
        id: 90,
        workflowId: 6,
        workflowName: 'Daily sales report',
        runNumber: 3,
        status: 'FAILED',
        triggerType: 'SCHEDULE',
        createdAt: '2026-09-28T06:00:00Z',
        finishedAt: '2026-09-28T06:00:05Z',
        errorSummary: 'fetch_orders failed after 3 attempts: HTTP 503 Service Unavailable',
      },
    ],
    page: 0,
    size: 10,
    total: 14,
  },
  upcomingRuns: [
    {
      scheduleId: 3,
      workflowId: 6,
      workflowName: 'Daily sales report',
      cronExpression: '0 8 * * *',
      timezone: 'Europe/Berlin',
      nextRunAt: '2026-09-29T06:00:00Z',
    },
  ],
};

const PROJECT = {
  id: 7,
  name: 'Acme Shop Ops',
  description: null,
  workflowCount: 1,
  createdAt: '2026-09-01T10:00:00Z',
};

const WORKFLOW = {
  id: 5,
  projectId: 7,
  name: 'Customer onboarding',
  description: null,
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
};

describe('Dashboard', () => {
  let fixture: ComponentFixture<Dashboard>;
  let element: HTMLElement;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(Date.parse('2026-09-28T10:05:00Z'));
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Dashboard);
    element = fixture.nativeElement;
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
  });

  afterEach(() => {
    fixture.destroy();
    http.verify();
    vi.useRealTimers();
  });

  function flushWorkspace(): void {
    http.expectOne('/api/projects').flush([PROJECT]);
    http.expectOne('/api/projects/7/workflows').flush([WORKFLOW]);
    http
      .expectOne((request) => request.url === '/api/workflows/5/executions')
      .flush({ items: DATA.running.items, page: 0, size: 8, total: 12 });
    http.expectOne('/api/workflows/5/schedules').flush([]);
  }

  it('shows running executions, recent failures and upcoming runs', () => {
    flushWorkspace();
    http.expectOne('/api/dashboard').flush(DATA);
    fixture.detectChanges();

    expect(element.querySelector('.running')?.textContent).toContain(
      'Customer onboarding · run #12',
    );
    expect(element.querySelector('.running')?.textContent).toContain('Running for 5m 00s');
    expect(element.querySelector('.failures')?.textContent).toContain('14 failed');
    expect(element.querySelector('.failures')?.textContent).toContain('HTTP 503');
    expect(element.querySelector('.failures a[href="/executions"]')).not.toBeNull();
    expect(element.querySelector('.upcoming')?.textContent).toContain('08:00');
    expect(element.querySelector('.upcoming')?.textContent).toContain('Europe/Berlin');
  });

  it('summarises the current state in one line', () => {
    flushWorkspace();
    http.expectOne('/api/dashboard').flush(DATA);
    fixture.detectChanges();

    expect(element.querySelector('.pulse-line')?.textContent).toBe(
      '1 run in progress, 14 failed in the last 24 hours, next scheduled run in 19 h.',
    );
  });

  it('lists recently active workflows with their state', () => {
    flushWorkspace();
    http.expectOne('/api/dashboard').flush(DATA);
    fixture.detectChanges();

    const card = element.querySelector('app-workflow-card')!.textContent!.replace(/\s+/g, ' ');
    expect(card).toContain('Customer onboarding');
    expect(card).toContain('Triggered manually');
    expect(card).toContain('12 runs');
  });

  it('welcomes a user who has no workflows yet', () => {
    http.expectOne('/api/projects').flush([]);
    http.expectOne('/api/dashboard').flush({
      running: { items: [], page: 0, size: 10, total: 0 },
      failedLast24Hours: { items: [], page: 0, size: 10, total: 0 },
      upcomingRuns: [],
    });
    fixture.detectChanges();

    expect(element.querySelector('app-welcome')).not.toBeNull();
    expect(element.querySelector('a.create')?.getAttribute('href')).toBe('/workflows/new');
    expect(element.querySelectorAll('.templates > li')).toHaveLength(4);
    expect(element.querySelector('.running')).toBeNull();
  });

  it('refreshes every 10 seconds', () => {
    flushWorkspace();
    http.expectOne('/api/dashboard').flush(DATA);

    vi.advanceTimersByTime(DASHBOARD_REFRESH_MS - 1);
    http.expectNone('/api/dashboard');
    vi.advanceTimersByTime(1);
    http
      .expectOne('/api/dashboard')
      .flush({ ...DATA, running: { items: [], page: 0, size: 10, total: 0 } });
    fixture.detectChanges();

    expect(element.querySelector('.running')?.textContent).toContain('Nothing is running.');
  });
});
