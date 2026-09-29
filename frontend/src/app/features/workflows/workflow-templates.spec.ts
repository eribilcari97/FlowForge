import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import {
  WORKFLOW_TEMPLATES,
  WorkflowTemplateService,
  templateById,
  templateStages,
} from './workflow-templates';

describe('workflow templates', () => {
  it('only use valid step keys and depend on steps of the same template', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const keys = template.steps.map((step) => step.key);
      expect(new Set(keys).size).toBe(keys.length);
      for (const step of template.steps) {
        expect(step.key).toMatch(/^[a-z][a-z0-9_]{0,49}$/);
        expect(step.dependsOn.every((key) => keys.includes(key))).toBe(true);
      }
    }
  });

  it('only reference upstream steps in placeholders and expressions', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const stages = templateStages(template);
      const stageOf = new Map(
        stages.flatMap((stage, index) => stage.map((step) => [step.key, index] as const)),
      );
      for (const step of template.steps) {
        const referenced = [...JSON.stringify(step.config).matchAll(/steps\.([a-z0-9_]+)/g)].map(
          (match) => match[1],
        );
        for (const key of referenced) {
          expect(stageOf.get(key)!).toBeLessThan(stageOf.get(step.key)!);
        }
      }
    }
  });

  it('groups the onboarding steps into their parallel stages', () => {
    const stages = templateStages(templateById('customer-onboarding')!).map((stage) =>
      stage.map((step) => step.key),
    );

    expect(stages).toEqual([
      ['create_crm_contact', 'create_account'],
      ['wait_1_day'],
      ['notify_crm'],
    ]);
  });

  it('adds the steps in order and then connects them', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    let done = false;

    TestBed.inject(WorkflowTemplateService)
      .addSteps(9, templateById('daily-sales-report')!)
      .subscribe(() => (done = true));

    ['fetch_orders', 'calculate_revenue', 'send_report'].forEach((key, index) => {
      const request = http.expectOne({ method: 'POST', url: '/api/workflows/9/steps' });
      expect(request.request.body.key).toBe(key);
      request.flush({ ...request.request.body, id: 100 + index, dependsOn: [] });
    });
    const first = http.expectOne('/api/workflows/9/steps/101/dependencies');
    expect(first.request.body).toEqual({ dependsOn: [100] });
    first.flush({ stepId: 101, dependsOn: [100] });
    const second = http.expectOne('/api/workflows/9/steps/102/dependencies');
    expect(second.request.body).toEqual({ dependsOn: [101] });
    second.flush({ stepId: 102, dependsOn: [101] });

    expect(done).toBe(true);
    http.verify();
  });
});
