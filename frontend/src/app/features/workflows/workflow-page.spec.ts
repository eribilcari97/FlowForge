import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { WorkflowPage } from './workflow-page';
import { Workflow } from './workflow.service';

const WORKFLOW: Workflow = {
  id: 5,
  projectId: 7,
  name: 'Customer onboarding',
  description: null,
  status: 'DRAFT',
  version: 0,
  createdAt: '',
  updatedAt: '',
  steps: [
    {
      id: 11,
      key: 'fetch',
      name: 'Fetch',
      jobType: 'HTTP',
      config: { method: 'GET', url: 'https://example.com' },
      timeoutSeconds: 30,
      maxAttempts: 3,
      retryDelaySeconds: 10,
      dependsOn: [],
    },
    {
      id: 12,
      key: 'report',
      name: 'Report',
      jobType: 'DELAY',
      config: { duration: 'PT5S' },
      timeoutSeconds: 30,
      maxAttempts: 3,
      retryDelaySeconds: 10,
      dependsOn: [11],
    },
  ],
};

describe('WorkflowPage', () => {
  let fixture: ComponentFixture<WorkflowPage>;
  let element: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [WorkflowPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { paramMap: of(convertToParamMap({ id: '5' })) },
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WorkflowPage);
    element = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne('/api/workflows/5').flush(WORKFLOW);
    http
      .expectOne((request) => request.url === '/api/workflows/5/executions')
      .flush({
        items: [
          {
            id: 91,
            workflowId: 5,
            workflowName: 'Customer onboarding',
            runNumber: 3,
            status: 'CANCELLED',
            triggerType: 'MANUAL',
            createdAt: '2026-09-28T10:00:00Z',
            finishedAt: '2026-09-28T10:01:00Z',
            errorSummary: null,
          },
        ],
        page: 0,
        size: 5,
        total: 1,
      });
    await fixture.whenStable();
  });

  it('shows the recent runs of the workflow', () => {
    const runs = element.querySelector('.recent-runs')!.textContent!.replace(/\s+/g, ' ');

    expect(runs).toContain('Run #3');
    expect(runs).toContain('CANCELLED');
  });

  afterEach(() => http.verify());

  it('shows which steps each step waits for', () => {
    const dependencies = [...element.querySelectorAll('.dependencies')].map((e) =>
      e.textContent!.trim(),
    );

    expect(dependencies).toEqual(['Starts immediately', 'After fetch']);
  });

  it('lists the problems when activation fails', async () => {
    (element.querySelector('button.activate') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/workflows/5/activate' }).flush(
      {
        status: 422,
        code: 'WORKFLOW_INVALID',
        problems: [
          { stepKey: 'report', message: 'Unknown placeholder {{secret}}' },
          { stepKey: null, message: 'The workflow has no steps' },
        ],
      },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await fixture.whenStable();

    const problems = element.querySelector('.problems')!.textContent!.replace(/\s+/g, ' ');
    expect(problems).toContain('report:');
    expect(problems).toContain('Unknown placeholder {{secret}}');
    expect(problems).toContain('The workflow has no steps');
    expect(element.querySelector('.status')?.textContent).toContain('DRAFT');
  });

  it('shows the new status after a successful activation', async () => {
    (element.querySelector('button.activate') as HTMLButtonElement).click();
    http
      .expectOne('/api/workflows/5/activate')
      .flush({ ...WORKFLOW, status: 'ACTIVE', version: 1 });
    await fixture.whenStable();

    expect(element.querySelector('.status')?.textContent).toContain('ACTIVE');
    expect(element.querySelector('button.activate')).toBeNull();
    expect(element.querySelector('button.deactivate')).not.toBeNull();
  });

  it('opens the dependency picker for a step and applies the saved list', async () => {
    (element.querySelectorAll('button.edit-dependencies')[0] as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(element.querySelector('app-dependency-picker')).not.toBeNull();

    fixture.componentInstance.dependenciesSaved(WORKFLOW, WORKFLOW.steps[0], []);
    await fixture.whenStable();

    expect(element.querySelector('app-dependency-picker')).toBeNull();
  });
});
