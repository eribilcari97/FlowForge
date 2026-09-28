import { FormControl } from '@angular/forms';

import { jsonObjectValidator, jsonValidator, stepSummary, toHttpConfig } from './step-config';
import { Step } from './workflow.service';

describe('step config helpers', () => {
  it('leaves optional HTTP fields out when they are empty', () => {
    expect(
      toHttpConfig({
        method: 'GET',
        url: ' https://example.com ',
        headers: '',
        body: ' ',
        expectedStatus: '',
      }),
    ).toEqual({ method: 'GET', url: 'https://example.com' });
  });

  it('accepts any JSON as a body but only objects as headers', () => {
    expect(jsonValidator(new FormControl('[1, 2]'))).toBeNull();
    expect(jsonValidator(new FormControl('{ broken'))).toEqual({ json: true });
    expect(jsonObjectValidator(new FormControl('{"Accept": "text/plain"}'))).toBeNull();
    expect(jsonObjectValidator(new FormControl('["Accept"]'))).toEqual({ jsonObject: true });
    expect(jsonObjectValidator(new FormControl(''))).toBeNull();
  });

  it('summarizes steps for the step list', () => {
    const base = {
      id: 1,
      key: 'k',
      name: 'n',
      timeoutSeconds: 30,
      maxAttempts: 3,
      retryDelaySeconds: 10,
      dependsOn: [],
    };
    const httpStep: Step = {
      ...base,
      jobType: 'HTTP',
      config: { method: 'PUT', url: 'https://x.test/a' },
    };
    const delayStep: Step = { ...base, jobType: 'DELAY', config: { duration: 'P1D' } };

    const transformStep: Step = {
      ...base,
      jobType: 'TRANSFORM',
      config: { expression: '$sum(\n  steps.a.output.body.total\n)' },
    };
    const emailStep: Step = {
      ...base,
      jobType: 'EMAIL',
      config: { to: ['a@x.test', 'b@x.test'], subject: 'Report', text: 'Hi' },
    };

    expect(stepSummary(httpStep)).toBe('PUT https://x.test/a');
    expect(stepSummary(delayStep)).toBe('Wait P1D');
    expect(stepSummary(transformStep)).toBe('JSONata: $sum( steps.a.output.body.total )');
    expect(stepSummary(emailStep)).toBe('Email to a@x.test, b@x.test: Report');
  });
});
