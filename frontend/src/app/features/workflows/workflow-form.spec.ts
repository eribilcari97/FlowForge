import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import type { MockInstance } from 'vitest';

import { WorkflowForm } from './workflow-form';

const WORKFLOW = {
  id: 5,
  projectId: 7,
  name: 'Reports',
  description: null,
  status: 'DRAFT',
  version: 3,
  steps: [],
  createdAt: '',
  updatedAt: '',
};

describe('WorkflowForm', () => {
  let fixture: ComponentFixture<WorkflowForm>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let navigate: MockInstance<Router['navigate']>;

  async function setUp(params: Record<string, string>) {
    TestBed.configureTestingModule({
      imports: [WorkflowForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { paramMap: convertToParamMap(params) } },
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WorkflowForm);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  async function submit(name: string) {
    fixture.componentInstance['form'].patchValue({ name });
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('creates a workflow in the project and opens it', async () => {
    await setUp({ projectId: '7' });

    await submit('Reports');
    const request = http.expectOne({ method: 'POST', url: '/api/projects/7/workflows' });
    expect(request.request.body).toEqual({ name: 'Reports', description: null });
    request.flush(WORKFLOW);

    expect(navigate).toHaveBeenCalledWith(['/workflows', 5]);
  });

  it('sends the version it loaded when saving an edit', async () => {
    await setUp({ id: '5' });
    http.expectOne('/api/workflows/5').flush(WORKFLOW);

    await submit('Daily reports');

    const request = http.expectOne({ method: 'PUT', url: '/api/workflows/5' });
    expect(request.request.body).toEqual({ name: 'Daily reports', description: null, version: 3 });
    request.flush({ ...WORKFLOW, name: 'Daily reports', version: 4 });
  });

  it('explains a version conflict instead of overwriting the other change', async () => {
    await setUp({ id: '5' });
    http.expectOne('/api/workflows/5').flush(WORKFLOW);

    await submit('Daily reports');
    http
      .expectOne('/api/workflows/5')
      .flush({ status: 409, code: 'VERSION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'Someone else changed this workflow',
    );
    expect(navigate).not.toHaveBeenCalled();
  });
});
