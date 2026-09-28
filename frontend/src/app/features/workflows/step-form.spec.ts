import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import type { MockInstance } from 'vitest';

import { StepForm } from './step-form';
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
      key: 'create_crm_contact',
      name: 'Create CRM contact',
      jobType: 'HTTP',
      config: {
        method: 'POST',
        url: 'https://crm.example.com/api/contacts',
        headers: { Accept: 'application/json' },
        body: { email: '{{input.customer.email}}' },
        expectedStatus: [200, 201],
      },
      timeoutSeconds: 30,
      maxAttempts: 3,
      retryDelaySeconds: 10,
      dependsOn: [],
    },
  ],
};

describe('StepForm', () => {
  let fixture: ComponentFixture<StepForm>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let navigate: MockInstance<Router['navigate']>;

  async function setUp(params: Record<string, string>) {
    TestBed.configureTestingModule({
      imports: [StepForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { paramMap: convertToParamMap(params) } },
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StepForm);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  function form() {
    return fixture.componentInstance['form'];
  }

  async function submit() {
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('sends an HTTP step with headers, body and expected status parsed from the form', async () => {
    await setUp({ id: '5' });
    form().patchValue({ key: 'create_crm_contact', name: 'Create CRM contact', maxAttempts: 5 });
    form().controls.http.setValue({
      method: 'POST',
      url: 'https://crm.example.com/api/contacts',
      headers: '{"Accept": "application/json"}',
      body: '{"email": "{{input.customer.email}}"}',
      expectedStatus: '200, 201',
    });

    await submit();

    const request = http.expectOne({ method: 'POST', url: '/api/workflows/5/steps' });
    expect(request.request.body).toEqual({
      key: 'create_crm_contact',
      jobType: 'HTTP',
      name: 'Create CRM contact',
      config: {
        method: 'POST',
        url: 'https://crm.example.com/api/contacts',
        headers: { Accept: 'application/json' },
        body: { email: '{{input.customer.email}}' },
        expectedStatus: [200, 201],
      },
      timeoutSeconds: 30,
      maxAttempts: 5,
      retryDelaySeconds: 10,
    });
    request.flush({ ...WORKFLOW.steps[0], id: 12 });
    expect(navigate).toHaveBeenCalledWith(['/workflows', 5]);
  });

  it('swaps the config fields when the job type changes and sends a DELAY config', async () => {
    await setUp({ id: '5' });
    expect(element.querySelector('.http-config')).not.toBeNull();
    expect(element.querySelector('.delay-config')).toBeNull();

    form().controls.jobType.setValue('DELAY');
    await fixture.whenStable();

    expect(element.querySelector('.http-config')).toBeNull();
    expect(element.querySelector('.delay-config')).not.toBeNull();

    form().patchValue({ key: 'wait_1_day', name: 'Wait one day' });
    form().controls.delay.setValue({ duration: 'P1D' });
    await submit();

    const request = http.expectOne('/api/workflows/5/steps');
    expect(request.request.body.jobType).toBe('DELAY');
    expect(request.request.body.config).toEqual({ duration: 'P1D' });
    request.flush({});
  });

  it('sends a TRANSFORM step with its expression', async () => {
    await setUp({ id: '5' });
    form().controls.jobType.setValue('TRANSFORM');
    await fixture.whenStable();
    expect(element.querySelector('.transform-config')).not.toBeNull();

    form().patchValue({ key: 'calculate_revenue', name: 'Calculate revenue' });
    form().controls.transform.setValue({
      expression: "  $sum(steps.fetch_orders.output.body.orders[status='PAID'].total)  ",
    });
    await submit();

    const request = http.expectOne('/api/workflows/5/steps');
    expect(request.request.body.jobType).toBe('TRANSFORM');
    expect(request.request.body.config).toEqual({
      expression: "$sum(steps.fetch_orders.output.body.orders[status='PAID'].total)",
    });
    request.flush({});
  });

  it('sends an EMAIL step with split recipients and only the bodies that were filled in', async () => {
    await setUp({ id: '5' });
    form().controls.jobType.setValue('EMAIL');
    await fixture.whenStable();
    expect(element.querySelector('.email-config')).not.toBeNull();

    form().patchValue({ key: 'send_report', name: 'Send report' });
    form().controls.email.setValue({
      to: '{{input.recipient}}, finance@example.com',
      cc: '',
      subject: 'Daily sales',
      text: 'Revenue: {{steps.calculate_revenue.output.revenue}}',
      html: '',
    });
    await submit();

    const request = http.expectOne('/api/workflows/5/steps');
    expect(request.request.body.config).toEqual({
      to: ['{{input.recipient}}', 'finance@example.com'],
      subject: 'Daily sales',
      text: 'Revenue: {{steps.calculate_revenue.output.revenue}}',
    });
    request.flush({});
  });

  it('requires a text or an HTML body for an email', async () => {
    await setUp({ id: '5' });
    form().controls.jobType.setValue('EMAIL');
    form().patchValue({ key: 'send_report', name: 'Send report' });
    form().controls.email.patchValue({ to: 'ops@example.com', subject: 'Report' });

    await submit();

    http.expectNone('/api/workflows/5/steps');
    expect(form().controls.email.controls.text.hasError('textOrHtml')).toBe(true);

    form().controls.email.patchValue({ html: '<p>Report</p>' });
    expect(form().controls.email.valid).toBe(true);
  });

  it('does not send the request while the HTTP body is not valid JSON', async () => {
    await setUp({ id: '5' });
    form().patchValue({ key: 'fetch', name: 'Fetch' });
    form().controls.http.patchValue({ url: 'https://example.com', body: '{ not json' });

    await submit();

    http.expectNone('/api/workflows/5/steps');
    expect(form().controls.http.controls.body.hasError('json')).toBe(true);
  });

  it('shows server validation errors next to the matching config field', async () => {
    await setUp({ id: '5' });
    form().patchValue({ key: 'fetch', name: 'Fetch' });
    form().controls.http.patchValue({ url: 'https://{{input.host}}' });

    await submit();
    http.expectOne('/api/workflows/5/steps').flush(
      {
        status: 400,
        code: 'VALIDATION_ERROR',
        errors: [{ field: 'config.url', message: 'must be an absolute http or https URL' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    expect(form().controls.http.controls.url.getError('server')).toBe(
      'must be an absolute http or https URL',
    );
    expect(element.textContent).toContain('must be an absolute http or https URL');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('marks the key when the workflow already has a step with it', async () => {
    await setUp({ id: '5' });
    form().patchValue({ key: 'fetch', name: 'Fetch' });
    form().controls.http.patchValue({ url: 'https://example.com' });

    await submit();
    http
      .expectOne('/api/workflows/5/steps')
      .flush({ status: 409, code: 'DUPLICATE_NAME' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(element.textContent).toContain('This workflow already has a step with this key');
  });

  it('edits an existing step without sending its key or job type', async () => {
    await setUp({ id: '5', stepId: '11' });
    http.expectOne('/api/workflows/5').flush(WORKFLOW);
    await fixture.whenStable();

    expect(form().controls.key.disabled).toBe(true);
    expect(form().controls.jobType.disabled).toBe(true);
    expect(form().controls.http.controls.expectedStatus.value).toBe('200, 201');

    form().patchValue({ name: 'Create contact in CRM' });
    await submit();

    const request = http.expectOne({ method: 'PUT', url: '/api/workflows/5/steps/11' });
    expect(request.request.body).not.toHaveProperty('key');
    expect(request.request.body).not.toHaveProperty('jobType');
    expect(request.request.body.name).toBe('Create contact in CRM');
    expect(request.request.body.config).toEqual(WORKFLOW.steps[0].config);
    request.flush(WORKFLOW.steps[0]);
  });

  it('shows not found for a step that is not part of the workflow', async () => {
    await setUp({ id: '5', stepId: '99' });
    http.expectOne('/api/workflows/5').flush(WORKFLOW);
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain('Step not found.');
  });
});
