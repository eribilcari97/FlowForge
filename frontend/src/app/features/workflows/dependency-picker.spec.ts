import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DependencyPicker } from './dependency-picker';
import { Step } from './workflow.service';

function step(id: number, key: string, dependsOn: number[] = []): Step {
  return {
    id,
    key,
    name: key,
    jobType: 'DELAY',
    config: { duration: 'PT1S' },
    timeoutSeconds: 30,
    maxAttempts: 3,
    retryDelaySeconds: 10,
    dependsOn,
  };
}

describe('DependencyPicker', () => {
  const steps = [
    step(1, 'fetch'),
    step(2, 'transform', [1]),
    step(3, 'report', [2]),
    step(4, 'audit'),
  ];
  let fixture: ComponentFixture<DependencyPicker>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let saved: number[] | undefined;

  async function setUp(editedStep: Step) {
    TestBed.configureTestingModule({
      imports: [DependencyPicker],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DependencyPicker);
    fixture.componentRef.setInput('workflowId', 5);
    fixture.componentRef.setInput('steps', steps);
    fixture.componentRef.setInput('step', editedStep);
    saved = undefined;
    fixture.componentInstance.saved.subscribe((dependsOn) => (saved = dependsOn));
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  function optionLabels(): string[] {
    return [...element.querySelectorAll('mat-checkbox')].map((box) =>
      box.textContent!.replace(/\s+/g, ' ').trim(),
    );
  }

  afterEach(() => http.verify());

  it('offers only steps that cannot create a cycle and pre-selects the current dependencies', async () => {
    await setUp(steps[1]);

    expect(optionLabels()).toEqual(['fetch · fetch', 'audit · audit']);
    expect(fixture.componentInstance['selected']()).toEqual(new Set([1]));
  });

  it('saves the whole selection as the new dependency list', async () => {
    await setUp(steps[2]);
    fixture.componentInstance.toggle(2, false);
    fixture.componentInstance.toggle(4, true);
    fixture.componentInstance.toggle(1, true);

    fixture.componentInstance.save();

    const request = http.expectOne({ method: 'PUT', url: '/api/workflows/5/steps/3/dependencies' });
    expect(request.request.body).toEqual({ dependsOn: [1, 4] });
    request.flush({ stepId: 3, dependsOn: [1, 4] });
    expect(saved).toEqual([1, 4]);
  });

  it('shows the cycle reported by the server', async () => {
    await setUp(steps[3]);
    fixture.componentInstance.toggle(3, true);

    fixture.componentInstance.save();
    http.expectOne('/api/workflows/5/steps/4/dependencies').flush(
      {
        status: 422,
        code: 'DEPENDENCY_CYCLE',
        detail: 'This would create a cycle: report → audit → report',
        cycle: ['report', 'audit', 'report'],
      },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'This would create a cycle: report → audit → report',
    );
    expect(saved).toBeUndefined();
  });
});
