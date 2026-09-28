import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { problemOf } from '../../core/api/problem';
import {
  DelayConfigForm,
  EmailConfigForm,
  HttpConfigForm,
  TransformConfigForm,
  delayConfigForm,
  emailConfigForm,
  emptyDelayConfig,
  emptyEmailConfig,
  emptyHttpConfig,
  emptyTransformConfig,
  httpConfigForm,
  jsonObjectValidator,
  jsonValidator,
  toDelayConfig,
  toEmailConfig,
  toHttpConfig,
  toTransformConfig,
  transformConfigForm,
} from './step-config';
import {
  HttpMethod,
  JobType,
  Step,
  StepConfig,
  StepUpdateRequest,
  WorkflowService,
} from './workflow.service';

const ERROR_MESSAGES: Record<string, string> = {
  required: 'Required',
  json: 'Must be valid JSON',
  jsonObject: 'Must be a JSON object, for example {"Accept": "application/json"}',
  duplicate: 'This workflow already has a step with this key',
  textOrHtml: 'Enter a text body, an HTML body or both',
};

type ConfigGroup = 'http' | 'delay' | 'transform' | 'email';

const CONFIG_GROUPS: Record<JobType, ConfigGroup> = {
  HTTP: 'http',
  DELAY: 'delay',
  TRANSFORM: 'transform',
  EMAIL: 'email',
};

function textOrHtmlRequired(group: AbstractControl): ValidationErrors | null {
  const text = String(group.get('text')?.value ?? '').trim();
  const html = String(group.get('html')?.value ?? '').trim();
  if (text || html) {
    group.get('text')?.setErrors(null);
    return null;
  }
  group.get('text')?.setErrors({ textOrHtml: true });
  return { textOrHtml: true };
}

function configFor(value: {
  jobType: JobType;
  http: HttpConfigForm;
  delay: DelayConfigForm;
  transform: TransformConfigForm;
  email: EmailConfigForm;
}): StepConfig {
  switch (value.jobType) {
    case 'HTTP':
      return toHttpConfig(value.http);
    case 'DELAY':
      return toDelayConfig(value.delay);
    case 'TRANSFORM':
      return toTransformConfig(value.transform);
    case 'EMAIL':
      return toEmailConfig(value.email);
  }
}

@Component({
  selector: 'app-step-form',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  styleUrl: './workflows.scss',
  template: `
    <mat-card appearance="outlined" class="form-card">
      <mat-card-header>
        <mat-card-title>{{ stepId ? 'Edit step' : 'New step' }}</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        @if (notFound()) {
          <p class="page-error" role="alert">Step not found.</p>
        } @else {
          <form [formGroup]="form" (ngSubmit)="submit()">
            <div class="row">
              <mat-form-field>
                <mat-label>Key</mat-label>
                <input matInput formControlName="key" />
                <mat-hint>Used in placeholders. Can't be changed later.</mat-hint>
                <mat-error>{{ errorOf(form.controls.key) }}</mat-error>
              </mat-form-field>
              <mat-form-field>
                <mat-label>Job type</mat-label>
                <mat-select formControlName="jobType">
                  <mat-option value="HTTP">HTTP request</mat-option>
                  <mat-option value="DELAY">Delay</mat-option>
                  <mat-option value="TRANSFORM">Transform (JSONata)</mat-option>
                  <mat-option value="EMAIL">Email</mat-option>
                </mat-select>
                <mat-hint>Can't be changed later.</mat-hint>
              </mat-form-field>
            </div>

            <mat-form-field>
              <mat-label>Name</mat-label>
              <input matInput formControlName="name" />
              <mat-error>{{ errorOf(form.controls.name) }}</mat-error>
            </mat-form-field>

            @switch (form.controls.jobType.value) {
              @case ('HTTP') {
                <fieldset formGroupName="http" class="config http-config">
                  <legend>HTTP request</legend>
                  <div class="row">
                    <mat-form-field class="method">
                      <mat-label>Method</mat-label>
                      <mat-select formControlName="method">
                        @for (method of methods; track method) {
                          <mat-option [value]="method">{{ method }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>
                    <mat-form-field class="grow">
                      <mat-label>URL</mat-label>
                      <input
                        matInput
                        formControlName="url"
                        placeholder="https://api.example.com/orders"
                      />
                      <mat-error>{{ errorOf(form.controls.http.controls.url) }}</mat-error>
                    </mat-form-field>
                  </div>
                  <mat-form-field>
                    <mat-label>Headers (JSON object)</mat-label>
                    <textarea matInput formControlName="headers" rows="3"></textarea>
                    <mat-error>{{ errorOf(form.controls.http.controls.headers) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>Body (JSON)</mat-label>
                    <textarea matInput formControlName="body" rows="4"></textarea>
                    <mat-hint
                      >Placeholders like {{ placeholderExample }} are allowed inside
                      strings.</mat-hint
                    >
                    <mat-error>{{ errorOf(form.controls.http.controls.body) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>Expected status codes</mat-label>
                    <input matInput formControlName="expectedStatus" placeholder="200, 201" />
                    <mat-hint>Empty means any 2xx.</mat-hint>
                    <mat-error>{{ errorOf(form.controls.http.controls.expectedStatus) }}</mat-error>
                  </mat-form-field>
                </fieldset>
              }
              @case ('TRANSFORM') {
                <fieldset formGroupName="transform" class="config transform-config">
                  <legend>Transform</legend>
                  <mat-form-field>
                    <mat-label>JSONata expression</mat-label>
                    <textarea matInput formControlName="expression" rows="6"></textarea>
                    <mat-hint
                      >Reads input, steps.&lt;key&gt;.output and execution, for example
                      {{ transformExample }}</mat-hint
                    >
                    <mat-error>{{
                      errorOf(form.controls.transform.controls.expression)
                    }}</mat-error>
                  </mat-form-field>
                </fieldset>
              }
              @case ('EMAIL') {
                <fieldset formGroupName="email" class="config email-config">
                  <legend>Email</legend>
                  <mat-form-field>
                    <mat-label>To (comma-separated)</mat-label>
                    <input matInput formControlName="to" />
                    <mat-error>{{ errorOf(form.controls.email.controls.to) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>Cc (optional)</mat-label>
                    <input matInput formControlName="cc" />
                    <mat-error>{{ errorOf(form.controls.email.controls.cc) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>Subject</mat-label>
                    <input matInput formControlName="subject" />
                    <mat-error>{{ errorOf(form.controls.email.controls.subject) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>Text body</mat-label>
                    <textarea matInput formControlName="text" rows="4"></textarea>
                    <mat-hint>Text, HTML or both. Placeholders are allowed everywhere.</mat-hint>
                    <mat-error>{{ errorOf(form.controls.email.controls.text) }}</mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>HTML body (optional)</mat-label>
                    <textarea matInput formControlName="html" rows="4"></textarea>
                  </mat-form-field>
                  <p class="limits">
                    An email is never sent twice automatically: if the outcome of a send is unknown,
                    the job fails instead of retrying.
                  </p>
                </fieldset>
              }
              @default {
                <fieldset formGroupName="delay" class="config delay-config">
                  <legend>Delay</legend>
                  <mat-form-field>
                    <mat-label>Duration (ISO-8601)</mat-label>
                    <input matInput formControlName="duration" />
                    <mat-hint>From PT1S to P7D, for example PT30S, PT5M or P1D.</mat-hint>
                    <mat-error>{{ errorOf(form.controls.delay.controls.duration) }}</mat-error>
                  </mat-form-field>
                </fieldset>
              }
            }

            <div class="row">
              <mat-form-field>
                <mat-label>Timeout (s)</mat-label>
                <input matInput type="number" formControlName="timeoutSeconds" />
                <mat-error>{{ errorOf(form.controls.timeoutSeconds) }}</mat-error>
              </mat-form-field>
              <mat-form-field>
                <mat-label>Max attempts</mat-label>
                <input matInput type="number" formControlName="maxAttempts" />
                <mat-error>{{ errorOf(form.controls.maxAttempts) }}</mat-error>
              </mat-form-field>
              <mat-form-field>
                <mat-label>Retry delay (s)</mat-label>
                <input matInput type="number" formControlName="retryDelaySeconds" />
                <mat-error>{{ errorOf(form.controls.retryDelaySeconds) }}</mat-error>
              </mat-form-field>
            </div>

            @for (message of errors(); track message) {
              <p class="page-error" role="alert">{{ message }}</p>
            }
            <div class="actions">
              <a mat-button [routerLink]="['/workflows', workflowId]">Cancel</a>
              <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
            </div>
          </form>
        }
      </mat-card-content>
    </mat-card>
  `,
})
export class StepForm {
  private readonly workflows = inject(WorkflowService);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.paramMap;
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly workflowId = Number(this.params.get('id'));
  protected readonly stepId = this.params.has('stepId') ? Number(this.params.get('stepId')) : null;
  protected readonly methods: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
  protected readonly placeholderExample = '{{input.customer.email}}';
  protected readonly transformExample =
    "$sum(steps.fetch_orders.output.body.orders[status='PAID'].total)";

  protected readonly form = this.fb.group({
    key: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9_]{0,49}$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    jobType: ['HTTP' as JobType, Validators.required],
    http: this.fb.group({
      method: [emptyHttpConfig.method, Validators.required],
      url: [emptyHttpConfig.url, [Validators.required, Validators.pattern(/^https?:\/\/\S+$/i)]],
      headers: [emptyHttpConfig.headers, jsonObjectValidator],
      body: [emptyHttpConfig.body, jsonValidator],
      expectedStatus: [
        emptyHttpConfig.expectedStatus,
        Validators.pattern(/^\s*[1-5]\d{2}(\s*,\s*[1-5]\d{2})*\s*$/),
      ],
    }),
    delay: this.fb.group({
      duration: [emptyDelayConfig.duration, Validators.required],
    }),
    transform: this.fb.group({
      expression: [emptyTransformConfig.expression, Validators.required],
    }),
    email: this.fb.group(
      {
        to: [emptyEmailConfig.to, Validators.required],
        cc: [emptyEmailConfig.cc],
        subject: [emptyEmailConfig.subject, [Validators.required, Validators.maxLength(300)]],
        text: [emptyEmailConfig.text],
        html: [emptyEmailConfig.html],
      },
      { validators: textOrHtmlRequired },
    ),
    timeoutSeconds: [30, [Validators.required, Validators.min(1), Validators.max(300)]],
    maxAttempts: [3, [Validators.required, Validators.min(1), Validators.max(10)]],
    retryDelaySeconds: [10, [Validators.required, Validators.min(1), Validators.max(3600)]],
  });

  protected readonly saving = signal(false);
  protected readonly notFound = signal(false);
  protected readonly errors = signal<string[]>([]);

  constructor() {
    this.form.controls.jobType.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((jobType) => this.showConfigFor(jobType));
    this.showConfigFor(this.form.controls.jobType.value);

    if (this.stepId) {
      this.form.controls.key.disable();
      this.form.controls.jobType.disable({ emitEvent: false });
      this.workflows.get(this.workflowId).subscribe({
        next: (workflow) => {
          const step = workflow.steps.find((s) => s.id === this.stepId);
          if (step) {
            this.load(step);
          } else {
            this.notFound.set(true);
          }
        },
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.errors.set(['Step could not be loaded.']),
      });
    }
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: StepUpdateRequest = {
      name: value.name.trim(),
      config: configFor(value),
      timeoutSeconds: value.timeoutSeconds,
      maxAttempts: value.maxAttempts,
      retryDelaySeconds: value.retryDelaySeconds,
    };
    const save = this.stepId
      ? this.workflows.updateStep(this.workflowId, this.stepId, request)
      : this.workflows.addStep(this.workflowId, {
          ...request,
          key: value.key,
          jobType: value.jobType,
        });

    this.saving.set(true);
    this.errors.set([]);
    save.subscribe({
      next: () => this.router.navigate(['/workflows', this.workflowId]),
      error: (error: unknown) => {
        this.saving.set(false);
        this.showServerError(error);
      },
    });
  }

  protected errorOf(control: AbstractControl): string {
    const errors = control.errors ?? {};
    if (errors['server']) {
      return errors['server'];
    }
    if (errors['min'] || errors['max']) {
      return 'Out of range';
    }
    if (errors['pattern']) {
      return 'Invalid format';
    }
    if (errors['maxlength']) {
      return 'Too long';
    }
    const key = Object.keys(errors)[0];
    return key ? (ERROR_MESSAGES[key] ?? 'Invalid') : '';
  }

  private showConfigFor(jobType: JobType): void {
    for (const [type, group] of Object.entries(CONFIG_GROUPS) as [JobType, ConfigGroup][]) {
      const control = this.form.controls[group];
      if (type === jobType) {
        control.enable({ emitEvent: false });
      } else {
        control.disable({ emitEvent: false });
      }
    }
  }

  private load(step: Step): void {
    this.form.patchValue({
      key: step.key,
      name: step.name,
      jobType: step.jobType,
      timeoutSeconds: step.timeoutSeconds,
      maxAttempts: step.maxAttempts,
      retryDelaySeconds: step.retryDelaySeconds,
    });
    switch (step.jobType) {
      case 'HTTP':
        this.form.controls.http.setValue(httpConfigForm(step));
        break;
      case 'DELAY':
        this.form.controls.delay.setValue(delayConfigForm(step));
        break;
      case 'TRANSFORM':
        this.form.controls.transform.setValue(transformConfigForm(step));
        break;
      case 'EMAIL':
        this.form.controls.email.setValue(emailConfigForm(step));
        break;
    }
    this.showConfigFor(step.jobType);
  }

  private showServerError(error: unknown): void {
    const problem = problemOf(error);
    if (problem?.code === 'DUPLICATE_NAME') {
      this.markServerError(this.form.controls.key, ERROR_MESSAGES['duplicate']);
      return;
    }
    if (problem?.errors?.length) {
      const unmatched: string[] = [];
      for (const fieldError of problem.errors) {
        const control = this.controlFor(fieldError.field);
        if (control) {
          this.markServerError(control, fieldError.message);
        } else {
          unmatched.push(`${fieldError.field}: ${fieldError.message}`);
        }
      }
      this.errors.set(unmatched);
      return;
    }
    this.errors.set([problem?.detail ?? 'Step could not be saved.']);
  }

  private controlFor(field: string): AbstractControl | null {
    if (field.startsWith('config.')) {
      const configField = field.substring('config.'.length).split('.')[0];
      return this.form.get([CONFIG_GROUPS[this.form.controls.jobType.value], configField]);
    }
    return this.form.get(field);
  }

  private markServerError(control: AbstractControl, message: string): void {
    control.setErrors({ server: message });
    control.markAsTouched();
  }
}
