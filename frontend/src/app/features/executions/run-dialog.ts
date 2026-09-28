import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { problemOf } from '../../core/api/problem';
import { jsonObjectValidator } from '../workflows/step-config';
import { Execution, ExecutionService } from './execution.service';

export interface RunDialogData {
  workflowId: number;
  workflowName: string;
  input?: Record<string, unknown>;
}

@Component({
  selector: 'app-run-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  template: `
    <h2 mat-dialog-title>Run {{ data.workflowName }}</h2>
    <mat-dialog-content>
      <mat-form-field class="input-field">
        <mat-label>Input (JSON object)</mat-label>
        <textarea matInput [formControl]="input" rows="8"></textarea>
        <mat-hint>Available to steps as {{ inputPlaceholder }}.</mat-hint>
        @if (input.hasError('jsonObject')) {
          <mat-error>Must be a JSON object, for example {{ exampleInput }}</mat-error>
        }
      </mat-form-field>
      @if (error(); as error) {
        <p class="dialog-error" role="alert">{{ error }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button class="run" (click)="run()" [disabled]="running()">Run</button>
    </mat-dialog-actions>
  `,
  styles: `
    .input-field {
      width: 100%;
      min-width: 420px;
    }

    .dialog-error {
      color: var(--mat-sys-error);
    }
  `,
})
export class RunDialog {
  private readonly executions = inject(ExecutionService);
  private readonly dialogRef = inject(MatDialogRef<RunDialog, Execution>);
  protected readonly data = inject<RunDialogData>(MAT_DIALOG_DATA);

  protected readonly inputPlaceholder = '{{input.<path>}}';
  protected readonly exampleInput = '{"customer": {"email": "jane@example.com"}}';
  private readonly idempotencyKey = crypto.randomUUID();

  protected readonly input = new FormControl(JSON.stringify(this.data.input ?? {}, null, 2), {
    nonNullable: true,
    validators: jsonObjectValidator,
  });
  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);

  run(): void {
    if (this.input.invalid) {
      this.input.markAsTouched();
      return;
    }
    const value = this.input.value.trim();
    const input = value ? (JSON.parse(value) as Record<string, unknown>) : {};
    this.running.set(true);
    this.error.set(null);
    this.executions.start(this.data.workflowId, input, this.idempotencyKey).subscribe({
      next: (execution) => this.dialogRef.close(execution),
      error: (error: unknown) => {
        this.running.set(false);
        const problem = problemOf(error);
        if (problem?.problems?.length) {
          this.error.set(problem.problems.map((p) => p.message).join('. '));
        } else {
          this.error.set(problem?.detail ?? 'The workflow could not be started. Try again.');
        }
      },
    });
  }
}
