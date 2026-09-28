import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import { RunDialog, RunDialogData } from './run-dialog';

describe('RunDialog', () => {
  let fixture: ComponentFixture<RunDialog>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function setUp(data: RunDialogData) {
    close = vi.fn();
    TestBed.configureTestingModule({
      imports: [RunDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RunDialog);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  function setInput(value: string) {
    fixture.componentInstance['input'].setValue(value);
  }

  afterEach(() => http.verify());

  it('starts the run with the JSON input and closes with the new execution', async () => {
    await setUp({ workflowId: 5, workflowName: 'Onboarding' });
    setInput('{"customer": {"email": "jane@example.com"}}');

    fixture.componentInstance.run();

    const request = http.expectOne('/api/workflows/5/executions');
    expect(request.request.body).toEqual({ input: { customer: { email: 'jane@example.com' } } });
    request.flush({ id: 91 }, { status: 202, statusText: 'Accepted' });
    expect(close).toHaveBeenCalledWith({ id: 91 });
  });

  it('reuses the same idempotency key when the user retries after an error', async () => {
    await setUp({ workflowId: 5, workflowName: 'Onboarding' });

    fixture.componentInstance.run();
    const first = http.expectOne('/api/workflows/5/executions');
    first.error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')?.textContent).toContain('could not be started');

    fixture.componentInstance.run();
    const second = http.expectOne('/api/workflows/5/executions');
    second.flush({ id: 91 });

    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
  });

  it('prefills the input of the run being repeated', async () => {
    await setUp({ workflowId: 5, workflowName: 'Onboarding', input: { plan: 'PRO' } });

    expect(JSON.parse(fixture.componentInstance['input'].value)).toEqual({ plan: 'PRO' });
  });

  it('does not start a run while the input is not a JSON object', async () => {
    await setUp({ workflowId: 5, workflowName: 'Onboarding' });
    setInput('[1, 2]');

    fixture.componentInstance.run();

    http.expectNone('/api/workflows/5/executions');
    expect(close).not.toHaveBeenCalled();
  });

  it('shows why the workflow could not be started', async () => {
    await setUp({ workflowId: 5, workflowName: 'Onboarding' });

    fixture.componentInstance.run();
    http
      .expectOne('/api/workflows/5/executions')
      .flush(
        { status: 409, code: 'INVALID_STATE', detail: 'Only active workflows can be run' },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'Only active workflows can be run',
    );
  });
});
