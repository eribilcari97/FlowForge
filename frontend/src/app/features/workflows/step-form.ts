import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { problemOf } from '../../core/api/problem';
import {
  delayConfigForm,
  emptyDelayConfig,
  emptyHttpConfig,
  httpConfigForm,
  jsonObjectValidator,
  jsonValidator,
  toDelayConfig,
  toHttpConfig,
} from './step-config';
import { HttpMethod, JobType, Step, StepUpdateRequest, WorkflowService } from './workflow.service';

const ERROR_MESSAGES: Record<string, string> = {
  required: 'Required',
  json: 'Must be valid JSON',
  jsonObject: 'Must be a JSON object, for example {"Accept": "application/json"}',
  duplicate: 'This workflow already has a step with this key',
};

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
                </mat-select>
                <mat-hint>Can't be changed later.</mat-hint>
              </mat-form-field>
            </div>

            <mat-form-field>
              <mat-label>Name</mat-label>
              <input matInput formControlName="name" />
              <mat-error>{{ errorOf(form.controls.name) }}</mat-error>
            </mat-form-field>

            @if (form.controls.jobType.value === 'HTTP') {
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
            } @else {
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
      config: value.jobType === 'HTTP' ? toHttpConfig(value.http) : toDelayConfig(value.delay),
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
    const { http, delay } = this.form.controls;
    if (jobType === 'HTTP') {
      http.enable({ emitEvent: false });
      delay.disable({ emitEvent: false });
    } else {
      delay.enable({ emitEvent: false });
      http.disable({ emitEvent: false });
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
    if (step.jobType === 'HTTP') {
      this.form.controls.http.setValue(httpConfigForm(step));
    } else {
      this.form.controls.delay.setValue(delayConfigForm(step));
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
      const group = this.form.controls.jobType.value === 'HTTP' ? 'http' : 'delay';
      return this.form.get([group, configField]);
    }
    return this.form.get(field);
  }

  private markServerError(control: AbstractControl, message: string): void {
    control.setErrors({ server: message });
    control.markAsTouched();
  }
}
