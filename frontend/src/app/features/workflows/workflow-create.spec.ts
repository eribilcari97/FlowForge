import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';

import { WorkflowCreate } from './workflow-create';

function setUp(query: Record<string, string>) {
  TestBed.configureTestingModule({
    imports: [WorkflowCreate],
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
  });
  TestBed.overrideProvider(ActivatedRoute, {
    useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
  });
  const http = TestBed.inject(HttpTestingController);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(WorkflowCreate);
  fixture.detectChanges();
  return { http, navigate, fixture, element: fixture.nativeElement as HTMLElement };
}

describe('WorkflowCreate', () => {
  it('creates a first project together with a blank workflow', async () => {
    const { http, navigate, fixture, element } = setUp({});
    http.expectOne('/api/projects').flush([]);
    await fixture.whenStable();

    const name = element.querySelector('input[formcontrolname=name]') as HTMLInputElement;
    name.value = 'Nightly backup';
    name.dispatchEvent(new Event('input'));
    (element.querySelector('button.submit') as HTMLButtonElement).click();

    const project = http.expectOne({ method: 'POST', url: '/api/projects' });
    expect(project.request.body).toEqual({ name: 'My workflows', description: null });
    project.flush({
      id: 3,
      name: 'My workflows',
      description: null,
      workflowCount: 0,
      createdAt: '',
    });
    const workflow = http.expectOne({ method: 'POST', url: '/api/projects/3/workflows' });
    expect(workflow.request.body).toEqual({ name: 'Nightly backup', description: null });
    workflow.flush({ id: 21, projectId: 3, name: 'Nightly backup', status: 'DRAFT', steps: [] });

    expect(navigate).toHaveBeenCalledWith(['/workflows', 21]);
    http.verify();
  });

  it('prefills the chosen template and adds its steps to the new workflow', async () => {
    const { http, navigate, fixture, element } = setUp({
      template: 'api-health-check',
      projectId: '8',
    });
    http.expectOne('/api/projects').flush([
      { id: 7, name: 'Ops', description: null, workflowCount: 0, createdAt: '' },
      { id: 8, name: 'Monitoring', description: null, workflowCount: 0, createdAt: '' },
    ]);
    await fixture.whenStable();

    const name = element.querySelector('input[formcontrolname=name]') as HTMLInputElement;
    expect(name.value).toBe('API health check');
    (element.querySelector('button.submit') as HTMLButtonElement).click();

    const workflow = http.expectOne({ method: 'POST', url: '/api/projects/8/workflows' });
    workflow.flush({ id: 22, projectId: 8, name: 'API health check', status: 'DRAFT', steps: [] });
    const step = http.expectOne({ method: 'POST', url: '/api/workflows/22/steps' });
    expect(step.request.body).toMatchObject({ key: 'check_api', jobType: 'HTTP' });
    step.flush({ ...step.request.body, id: 50, dependsOn: [] });

    expect(navigate).toHaveBeenCalledWith(['/workflows', 22]);
    http.verify();
  });

  it('shows a duplicate workflow name next to the name field', async () => {
    const { http, fixture, element } = setUp({ projectId: '7' });
    http
      .expectOne('/api/projects')
      .flush([{ id: 7, name: 'Ops', description: null, workflowCount: 1, createdAt: '' }]);
    await fixture.whenStable();

    const name = element.querySelector('input[formcontrolname=name]') as HTMLInputElement;
    name.value = 'Existing';
    name.dispatchEvent(new Event('input'));
    (element.querySelector('button.submit') as HTMLButtonElement).click();
    http
      .expectOne('/api/projects/7/workflows')
      .flush({ status: 409, code: 'DUPLICATE_NAME' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(element.querySelector('mat-error')?.textContent).toContain(
      'This project already has a workflow with this name',
    );
    http.verify();
  });
});
