import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { problemOf } from '../../core/api/problem';
import { ProjectRequest, ProjectService } from './project.service';

@Component({
  selector: 'app-project-form',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule],
  styleUrl: './projects.scss',
  template: `
    <h1 class="form-title">{{ projectId ? 'Edit project' : 'New project' }}</h1>
    <div class="panel form-card">
      @if (notFound()) {
        <p class="page-error" role="alert">Project not found.</p>
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
              <mat-error>You already have a project with this name</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Description</mat-label>
            <textarea matInput formControlName="description" rows="3"></textarea>
            @if (form.controls.description.hasError('maxlength')) {
              <mat-error>At most 1000 characters</mat-error>
            }
          </mat-form-field>
          @if (error(); as error) {
            <p class="page-error" role="alert">{{ error }}</p>
          }
          <div class="actions">
            <a mat-button [routerLink]="projectId ? ['/projects', projectId] : ['/projects']"
              >Cancel</a
            >
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
          </div>
        </form>
      }
    </div>
  `,
})
export class ProjectForm {
  private readonly projects = inject(ProjectService);
  private readonly router = inject(Router);

  private readonly routeId = inject(ActivatedRoute).snapshot.paramMap.get('id');
  protected readonly projectId = this.routeId ? Number(this.routeId) : null;
  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', Validators.maxLength(1000)],
  });
  protected readonly saving = signal(false);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.projectId) {
      this.projects.get(this.projectId).subscribe({
        next: (project) =>
          this.form.setValue({ name: project.name, description: project.description ?? '' }),
        error: (error: unknown) =>
          problemOf(error)?.status === 404
            ? this.notFound.set(true)
            : this.error.set('Project could not be loaded.'),
      });
    }
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, description } = this.form.getRawValue();
    const request: ProjectRequest = { name: name.trim(), description: description.trim() || null };
    const save = this.projectId
      ? this.projects.update(this.projectId, request)
      : this.projects.create(request);

    this.saving.set(true);
    this.error.set(null);
    save.subscribe({
      next: (project) => this.router.navigate(['/projects', project.id]),
      error: (error: unknown) => {
        this.saving.set(false);
        const problem = problemOf(error);
        if (problem?.code === 'DUPLICATE_NAME') {
          this.form.controls.name.setErrors({ duplicate: true });
          this.form.controls.name.markAsTouched();
        } else if (problem?.status === 404) {
          this.notFound.set(true);
        } else {
          this.error.set(problem?.detail ?? 'Project could not be saved.');
        }
      },
    });
  }
}
