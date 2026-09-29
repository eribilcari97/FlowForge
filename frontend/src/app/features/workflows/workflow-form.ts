import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { problemOf } from '../../core/api/problem';
import { Workflow, WorkflowService } from './workflow.service';

@Component({
  selector: 'app-workflow-form',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule],
  styleUrl: './workflows.scss',
  template: `
    <h1 class="form-title">{{ workflowId ? 'Edit workflow' : 'New workflow' }}</h1>
    <div class="panel form-card">
      @if (notFound()) {
        <p class="page-error" role="alert">Workflow not found.</p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field>
            <mat-label>Name</mat-label>
            <input matInput formControlName="name" />
            @if (form.controls.name.hasError('required')) {
              <mat-error>Name is required</mat-error>
            }
            @if (form.controls.name.hasError('maxlength')) {
              <mat-error>At most 100 characters</mat-error>
            }
            @if (form.controls.name.hasError('duplicate')) {
              <mat-error>This project already has a workflow with this name</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Description</mat-label>
            <textarea matInput formControlName="description" rows="3"></textarea>
            @if (form.controls.description.hasError('maxlength')) {
              <mat-error>At most 2000 characters</mat-error>
            }
          </mat-form-field>
          @if (error(); as error) {
            <p class="page-error" role="alert">{{ error }}</p>
          }
          <div class="actions">
            <a mat-button [routerLink]="cancelLink">Cancel</a>
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
          </div>
        </form>
      }
    </div>
  `,
})
export class WorkflowForm {
  private readonly workflows = inject(WorkflowService);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.paramMap;

  protected readonly workflowId = this.params.has('id') ? Number(this.params.get('id')) : null;
  private readonly projectId = this.params.has('projectId')
    ? Number(this.params.get('projectId'))
    : null;
  private version = 0;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', Validators.maxLength(2000)],
  });
  protected readonly saving = signal(false);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly cancelLink = this.workflowId
    ? ['/workflows', this.workflowId]
    : ['/projects', this.projectId];

  constructor() {
    if (this.workflowId) {
      this.workflows.get(this.workflowId).subscribe({
        next: (workflow) => this.load(workflow),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Workflow could not be loaded.'),
      });
    }
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, description } = this.form.getRawValue();
    const request = { name: name.trim(), description: description.trim() || null };
    const save = this.workflowId
      ? this.workflows.update(this.workflowId, { ...request, version: this.version })
      : this.workflows.create(this.projectId!, request);

    this.saving.set(true);
    this.error.set(null);
    save.subscribe({
      next: (workflow) => this.router.navigate(['/workflows', workflow.id]),
      error: (error: unknown) => {
        this.saving.set(false);
        const problem = problemOf(error);
        if (problem?.code === 'DUPLICATE_NAME') {
          this.form.controls.name.setErrors({ duplicate: true });
          this.form.controls.name.markAsTouched();
        } else if (problem?.code === 'VERSION_CONFLICT') {
          this.error.set(
            'Someone else changed this workflow in the meantime. Reload the page to see the latest version.',
          );
        } else if (problem?.status === 404) {
          this.notFound.set(true);
        } else {
          this.error.set(problem?.detail ?? 'Workflow could not be saved.');
        }
      },
    });
  }

  private load(workflow: Workflow): void {
    this.version = workflow.version;
    this.form.setValue({ name: workflow.name, description: workflow.description ?? '' });
  }
}
