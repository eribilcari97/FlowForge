import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';

import { problemOf } from '../../core/api/problem';
import { dependencyCandidates } from './workflow-graph';
import { Step, WorkflowService } from './workflow.service';

@Component({
  selector: 'app-dependency-picker',
  imports: [MatButtonModule, MatCheckboxModule],
  styleUrl: './workflows.scss',
  template: `
    <div class="dependency-picker">
      <p class="picker-title">
        <code>{{ step().key }}</code> starts after these steps have succeeded:
      </p>
      @if (candidates().length === 0) {
        <p class="empty">No other step can come before this one.</p>
      }
      @for (candidate of candidates(); track candidate.id) {
        <mat-checkbox
          [checked]="selected().has(candidate.id)"
          (change)="toggle(candidate.id, $event.checked)"
        >
          <code>{{ candidate.key }}</code> · {{ candidate.name }}
        </mat-checkbox>
      }
      @if (error(); as error) {
        <p class="page-error" role="alert">{{ error }}</p>
      }
      <div class="actions">
        <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
        <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">
          Save dependencies
        </button>
      </div>
    </div>
  `,
})
export class DependencyPicker implements OnInit {
  private readonly workflows = inject(WorkflowService);

  readonly workflowId = input.required<number>();
  readonly steps = input.required<Step[]>();
  readonly step = input.required<Step>();
  readonly saved = output<number[]>();
  readonly cancelled = output<void>();

  protected readonly candidates = computed(() =>
    dependencyCandidates(this.steps(), this.step().id),
  );
  protected readonly selected = signal(new Set<number>());
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.selected.set(new Set(this.step().dependsOn));
  }

  toggle(stepId: number, checked: boolean): void {
    const next = new Set(this.selected());
    if (checked) {
      next.add(stepId);
    } else {
      next.delete(stepId);
    }
    this.selected.set(next);
  }

  save(): void {
    this.saving.set(true);
    this.error.set(null);
    const dependsOn = [...this.selected()].sort((a, b) => a - b);
    this.workflows.setDependencies(this.workflowId(), this.step().id, dependsOn).subscribe({
      next: (result) => this.saved.emit(result.dependsOn),
      error: (error: unknown) => {
        this.saving.set(false);
        const problem = problemOf(error);
        if (problem?.errors?.length) {
          this.error.set(problem.errors.map((e) => e.message).join('. '));
        } else {
          this.error.set(problem?.detail ?? 'Dependencies could not be saved.');
        }
      },
    });
  }
}
