import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import type { MockInstance } from 'vitest';

import { ProjectForm } from './project-form';

describe('ProjectForm', () => {
  let fixture: ComponentFixture<ProjectForm>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let navigate: MockInstance<Router['navigate']>;

  async function setUp(projectId: string | null) {
    TestBed.configureTestingModule({
      imports: [ProjectForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { paramMap: convertToParamMap(projectId ? { id: projectId } : {}) } },
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProjectForm);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  async function submit(name: string, description: string) {
    fixture.componentInstance['form'].setValue({ name, description });
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('creates a project and opens it', async () => {
    await setUp(null);

    await submit('  Acme Shop Ops ', '');
    const request = http.expectOne({ method: 'POST', url: '/api/projects' });
    expect(request.request.body).toEqual({ name: 'Acme Shop Ops', description: null });
    request.flush({
      id: 1,
      name: 'Acme Shop Ops',
      description: null,
      workflowCount: 0,
      createdAt: '',
    });

    expect(navigate).toHaveBeenCalledWith(['/projects', 1]);
  });

  it('shows a duplicate name next to the name field', async () => {
    await setUp(null);

    await submit('Reports', '');
    http
      .expectOne('/api/projects')
      .flush({ status: 409, code: 'DUPLICATE_NAME' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(element.querySelector('mat-error')?.textContent).toContain(
      'You already have a project with this name',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('loads the project when editing and saves it with PUT', async () => {
    await setUp('1');
    http
      .expectOne('/api/projects/1')
      .flush({ id: 1, name: 'Old', description: 'Desc', workflowCount: 0, createdAt: '' });
    expect(fixture.componentInstance['form'].getRawValue()).toEqual({
      name: 'Old',
      description: 'Desc',
    });

    await submit('New', 'Desc');
    http
      .expectOne({ method: 'PUT', url: '/api/projects/1' })
      .flush({ id: 1, name: 'New', description: 'Desc', workflowCount: 0, createdAt: '' });

    expect(navigate).toHaveBeenCalledWith(['/projects', 1]);
  });

  it("shows not found for a project that doesn't exist or belongs to someone else", async () => {
    await setUp('1');
    http
      .expectOne('/api/projects/1')
      .flush({ status: 404, code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain('Project not found.');
    expect(element.querySelector('form')).toBeNull();
  });
});
