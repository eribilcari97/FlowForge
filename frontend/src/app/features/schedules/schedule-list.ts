import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { problemOf } from '../../core/api/problem';
import { StatusBadge } from '../../shared/status-badge';
import { jsonObjectValidator } from '../workflows/step-config';
import { WorkflowStatus } from '../workflows/workflow.service';
import { CRON_PRESETS, browserTimeZone, formatInZone, presetLabel } from './schedule-presets';
import { Schedule, ScheduleRequest, ScheduleService } from './schedule.service';

@Component({
  selector: 'app-schedule-list',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    StatusBadge,
  ],
  styleUrl: '../workflows/workflows.scss',
  template: `
    @if (workflowStatus() !== 'ACTIVE') {
      <p class="empty schedule-note">
        Schedules only fire while the workflow is active. Due runs are caught up once, when it is
        activated again.
      </p>
    }
    @if (error(); as error) {
      <p class="page-error" role="alert">{{ error }}</p>
    }

    @if (schedules().length === 0) {
      <p class="empty">No schedules yet.</p>
    } @else {
      <div class="table-scroll">
        <table class="schedules">
          <thead>
            <tr>
              <th>When</th>
              <th>Time zone</th>
              <th>Next run</th>
              <th>Last run</th>
              <th>State</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (schedule of schedules(); track schedule.id) {
              <tr>
                <td>
                  <code>{{ schedule.cronExpression }}</code>
                  @if (label(schedule.cronExpression); as label) {
                    <div class="limits">{{ label }}</div>
                  }
                </td>
                <td>{{ schedule.timezone }}</td>
                <td class="next-run">
                  {{ schedule.nextRunAt ? inZone(schedule.nextRunAt, schedule.timezone) : '—' }}
                </td>
                <td>
                  {{ schedule.lastRunAt ? inZone(schedule.lastRunAt, schedule.timezone) : '—' }}
                </td>
                <td><app-status-badge [status]="schedule.enabled ? 'ENABLED' : 'PAUSED'" /></td>
                <td class="step-actions">
                  @if (!readOnly()) {
                    <button
                      mat-button
                      class="toggle"
                      (click)="toggle(schedule)"
                      [disabled]="busy()"
                    >
                      {{ schedule.enabled ? 'Pause' : 'Resume' }}
                    </button>
                    <button mat-button (click)="edit(schedule)">Edit</button>
                    <button
                      mat-button
                      class="danger"
                      (click)="remove(schedule)"
                      [disabled]="busy()"
                    >
                      Delete
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    @if (!readOnly()) {
      <form [formGroup]="form" (ngSubmit)="save()" class="schedule-form">
        <h3>{{ editingId() ? 'Edit schedule' : 'Add a schedule' }}</h3>
        <div class="row">
          <mat-form-field>
            <mat-label>Preset</mat-label>
            <mat-select (valueChange)="applyPreset($event)" [value]="null">
              <mat-option [value]="null">Custom</mat-option>
              @for (preset of presets; track preset.cron) {
                <mat-option [value]="preset.cron">{{ preset.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Cron (minute hour day month weekday)</mat-label>
            <input matInput formControlName="cronExpression" />
            <mat-error>{{ errorOf(form.controls.cronExpression) }}</mat-error>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Time zone</mat-label>
            <input matInput formControlName="timezone" />
            <mat-error>{{ errorOf(form.controls.timezone) }}</mat-error>
          </mat-form-field>
        </div>
        <mat-form-field>
          <mat-label>Input (JSON object)</mat-label>
          <textarea matInput formControlName="input" rows="3"></textarea>
          <mat-error>{{ errorOf(form.controls.input) }}</mat-error>
        </mat-form-field>
        <mat-checkbox formControlName="enabled">Enabled</mat-checkbox>
        <div class="actions">
          @if (editingId()) {
            <button mat-button type="button" (click)="resetForm()">Cancel</button>
          }
          <button mat-flat-button type="submit" [disabled]="busy()">Save schedule</button>
        </div>
      </form>
    }
  `,
})
export class ScheduleList implements OnInit {
  private readonly scheduleService = inject(ScheduleService);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly workflowId = input.required<number>();
  readonly workflowStatus = input.required<WorkflowStatus>();
  readonly changed = output<Schedule[]>();

  protected readonly presets = CRON_PRESETS;
  protected readonly schedules = signal<Schedule[]>([]);
  protected readonly editingId = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    cronExpression: ['0 8 * * *', Validators.required],
    timezone: [browserTimeZone(), Validators.required],
    input: ['{}', jsonObjectValidator],
    enabled: [true],
  });

  ngOnInit(): void {
    this.load();
  }

  protected readOnly(): boolean {
    return this.workflowStatus() === 'ARCHIVED';
  }

  protected label(cron: string): string | null {
    return presetLabel(cron);
  }

  protected inZone(iso: string, timeZone: string): string {
    return formatInZone(iso, timeZone);
  }

  applyPreset(cron: string | null): void {
    if (cron) {
      this.form.controls.cronExpression.setValue(cron);
    }
  }

  edit(schedule: Schedule): void {
    this.editingId.set(schedule.id);
    this.form.setValue({
      cronExpression: schedule.cronExpression,
      timezone: schedule.timezone,
      input: JSON.stringify(schedule.input, null, 2),
      enabled: schedule.enabled,
    });
  }

  resetForm(): void {
    this.editingId.set(null);
    this.form.reset();
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: ScheduleRequest = {
      cronExpression: value.cronExpression.trim(),
      timezone: value.timezone.trim(),
      input: value.input.trim() ? JSON.parse(value.input) : {},
      enabled: value.enabled,
    };
    const id = this.editingId();
    const save = id
      ? this.scheduleService.update(this.workflowId(), id, request)
      : this.scheduleService.create(this.workflowId(), request);
    this.busy.set(true);
    this.error.set(null);
    save.subscribe({
      next: () => {
        this.busy.set(false);
        this.resetForm();
        this.load();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.showErrors(error);
      },
    });
  }

  toggle(schedule: Schedule): void {
    this.busy.set(true);
    this.error.set(null);
    this.scheduleService
      .update(this.workflowId(), schedule.id, {
        cronExpression: schedule.cronExpression,
        timezone: schedule.timezone,
        input: schedule.input,
        enabled: !schedule.enabled,
      })
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.setSchedules(this.schedules().map((s) => (s.id === updated.id ? updated : s)));
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.error.set(problemOf(error)?.detail ?? 'The schedule could not be changed.');
        },
      });
  }

  remove(schedule: Schedule): void {
    if (!confirm(`Delete the schedule "${schedule.cronExpression}"?`)) {
      return;
    }
    this.busy.set(true);
    this.scheduleService.delete(this.workflowId(), schedule.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.setSchedules(this.schedules().filter((s) => s.id !== schedule.id));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(problemOf(error)?.detail ?? 'The schedule could not be deleted.');
      },
    });
  }

  protected errorOf(control: AbstractControl): string {
    const errors = control.errors ?? {};
    if (errors['server']) {
      return errors['server'];
    }
    if (errors['jsonObject']) {
      return 'Must be a JSON object';
    }
    return errors['required'] ? 'Required' : '';
  }

  private load(): void {
    this.scheduleService.list(this.workflowId()).subscribe({
      next: (schedules) => this.setSchedules(schedules),
      error: () => this.error.set('Schedules could not be loaded.'),
    });
  }

  private setSchedules(schedules: Schedule[]): void {
    this.schedules.set(schedules);
    this.changed.emit(schedules);
  }

  private showErrors(error: unknown): void {
    const problem = problemOf(error);
    if (problem?.errors?.length) {
      for (const fieldError of problem.errors) {
        const control = this.form.get(fieldError.field);
        if (control) {
          control.setErrors({ server: fieldError.message });
          control.markAsTouched();
        }
      }
      return;
    }
    this.error.set(problem?.detail ?? 'The schedule could not be saved.');
  }
}
