import { Injectable, inject } from '@angular/core';
import { Observable, concatMap, from, last, map, of, toArray } from 'rxjs';

import { executionStages } from './workflow-graph';
import { JobType, StepConfig, WorkflowService } from './workflow.service';

export interface TemplateStep {
  key: string;
  name: string;
  jobType: JobType;
  config: StepConfig;
  maxAttempts: number;
  dependsOn: string[];
}

export interface WorkflowTemplate {
  id: string;
  name: string;
  description: string;
  trigger: string;
  steps: TemplateStep[];
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  {
    id: 'api-health-check',
    name: 'API health check',
    description: 'Calls a health endpoint and records every failure as a failed run.',
    trigger: 'Runs every 5 minutes',
    steps: [
      {
        key: 'check_api',
        name: 'Check API health',
        jobType: 'HTTP',
        config: { method: 'GET', url: 'https://api.example.com/health', expectedStatus: [200] },
        maxAttempts: 3,
        dependsOn: [],
      },
    ],
  },
  {
    id: 'customer-onboarding',
    name: 'Customer onboarding',
    description:
      'Creates the CRM contact and the account in parallel, waits a day, then adds a note to the contact.',
    trigger: 'Triggered manually, with the customer as input',
    steps: [
      {
        key: 'create_crm_contact',
        name: 'Create CRM contact',
        jobType: 'HTTP',
        config: {
          method: 'POST',
          url: 'https://crm.example.com/api/contacts',
          body: { email: '{{input.customer.email}}' },
        },
        maxAttempts: 1,
        dependsOn: [],
      },
      {
        key: 'create_account',
        name: 'Create account',
        jobType: 'HTTP',
        config: {
          method: 'PUT',
          url: 'https://app.example.com/api/accounts/{{input.customer.email}}',
          body: { plan: '{{input.customer.plan}}' },
        },
        maxAttempts: 3,
        dependsOn: [],
      },
      {
        key: 'wait_1_day',
        name: 'Wait one day',
        jobType: 'DELAY',
        config: { duration: 'P1D' },
        maxAttempts: 1,
        dependsOn: ['create_crm_contact', 'create_account'],
      },
      {
        key: 'notify_crm',
        name: 'Add note to contact',
        jobType: 'HTTP',
        config: {
          method: 'POST',
          url: 'https://crm.example.com/api/contacts/{{steps.create_crm_contact.output.body.id}}/notes',
          body: { text: 'Account {{steps.create_account.output.body.accountNumber}} is active' },
        },
        maxAttempts: 1,
        dependsOn: ['wait_1_day'],
      },
    ],
  },
  {
    id: 'daily-sales-report',
    name: 'Daily sales report',
    description: "Fetches yesterday's orders, totals them with JSONata and emails the result.",
    trigger: 'Runs every day at 08:00',
    steps: [
      {
        key: 'fetch_orders',
        name: 'Fetch orders',
        jobType: 'HTTP',
        config: { method: 'GET', url: 'https://shop.example.com/api/orders?since=yesterday' },
        maxAttempts: 3,
        dependsOn: [],
      },
      {
        key: 'calculate_revenue',
        name: 'Calculate revenue',
        jobType: 'TRANSFORM',
        config: {
          expression:
            '{ "orders": $count(steps.fetch_orders.output.body), "revenue": $sum(steps.fetch_orders.output.body.total) }',
        },
        maxAttempts: 1,
        dependsOn: ['fetch_orders'],
      },
      {
        key: 'send_report',
        name: 'Email the report',
        jobType: 'EMAIL',
        config: {
          to: ['team@example.com'],
          subject: 'Sales report',
          text: 'Orders: {{steps.calculate_revenue.output.orders}}\nRevenue: {{steps.calculate_revenue.output.revenue}}',
        },
        maxAttempts: 1,
        dependsOn: ['calculate_revenue'],
      },
    ],
  },
  {
    id: 'product-feed-sync',
    name: 'Product feed sync',
    description: 'Fetches the catalogue, keeps the products in stock and uploads them as a feed.',
    trigger: 'Runs every night',
    steps: [
      {
        key: 'fetch_products',
        name: 'Fetch products',
        jobType: 'HTTP',
        config: { method: 'GET', url: 'https://shop.example.com/api/products' },
        maxAttempts: 3,
        dependsOn: [],
      },
      {
        key: 'filter_products',
        name: 'Keep products in stock',
        jobType: 'TRANSFORM',
        config: { expression: 'steps.fetch_products.output.body[inStock = true]' },
        maxAttempts: 1,
        dependsOn: ['fetch_products'],
      },
      {
        key: 'upload_feed',
        name: 'Upload feed',
        jobType: 'HTTP',
        config: {
          method: 'PUT',
          url: 'https://feeds.example.com/api/feeds/products',
          body: '{{steps.filter_products.output}}',
        },
        maxAttempts: 3,
        dependsOn: ['filter_products'],
      },
    ],
  },
];

export function templateById(id: string | null): WorkflowTemplate | null {
  return WORKFLOW_TEMPLATES.find((template) => template.id === id) ?? null;
}

export function templateStages(template: WorkflowTemplate): TemplateStep[][] {
  const indexed = template.steps.map((step, index) => ({
    id: index,
    step,
    dependsOn: step.dependsOn.map((key) => template.steps.findIndex((s) => s.key === key)),
  }));
  return executionStages(indexed).map((stage) => stage.map((entry) => entry.step));
}

@Injectable({ providedIn: 'root' })
export class WorkflowTemplateService {
  private readonly workflows = inject(WorkflowService);

  addSteps(workflowId: number, template: WorkflowTemplate): Observable<void> {
    return from(template.steps).pipe(
      concatMap((step) =>
        this.workflows.addStep(workflowId, {
          key: step.key,
          name: step.name,
          jobType: step.jobType,
          config: step.config,
          timeoutSeconds: 30,
          maxAttempts: step.maxAttempts,
          retryDelaySeconds: 10,
        }),
      ),
      toArray(),
      concatMap((created) => {
        const idOf = new Map(created.map((step) => [step.key, step.id]));
        const withDependencies = template.steps.filter((step) => step.dependsOn.length > 0);
        if (withDependencies.length === 0) {
          return of(undefined);
        }
        return from(withDependencies).pipe(
          concatMap((step) =>
            this.workflows.setDependencies(
              workflowId,
              idOf.get(step.key)!,
              step.dependsOn.map((key) => idOf.get(key)!),
            ),
          ),
          last(),
          map(() => undefined),
        );
      }),
    );
  }
}
