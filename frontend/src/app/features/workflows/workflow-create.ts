import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, concatMap, map, of, tap } from 'rxjs';

import { problemOf } from '../../core/api/problem';
import { Project, ProjectService } from '../projects/project.service';
import { TemplatePipeline } from './template-pipeline';
import {
  WORKFLOW_TEMPLATES,
  WorkflowTemplate,
  WorkflowTemplateService,
  templateById,
} from './workflow-templates';
import { Workflow, WorkflowService } from './workflow.service';

const NEW_PROJECT = -1;

@Component({
  selector: 'app-workflow-create',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    TemplatePipeline,
  ],
  styleUrl: './workflow-create.scss',
  template: `
    <a class="crumb" routerLink="/workflows">Workflows</a>
    <h1 class="create-title">New workflow</h1>

    <form class="create-layout" [formGroup]="form" (ngSubmit)="submit()">
      <fieldset class="starts">
        <legend><h2>Start from</h2></legend>
        <label class="start" [class.selected]="template() === null">
          <input
            type="radio"
            name="start"
            [checked]="template() === null"
            (change)="choose(null)"
          />
          <span class="start-name">Blank workflow</span>
          <span class="start-desc">Add your own steps one by one.</span>
        </label>
        @for (option of templates; track option.id) {
          <label class="start" [class.selected]="template()?.id === option.id">
            <input
              type="radio"
              name="start"
              [value]="option.id"
              [checked]="template()?.id === option.id"
              (change)="choose(option)"
            />
            <span class="start-name">{{ option.name }}</span>
            <span class="start-desc">{{ option.description }}</span>
            <app-template-pipeline [template]="option" />
          </label>
        }
      </fieldset>

      <section class="panel details" aria-labelledby="details-title">
        <h2 id="details-title">Details</h2>
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
        @if (projects().length > 0) {
          <mat-form-field>
            <mat-label>Project</mat-label>
            <mat-select formControlName="projectId">
              @for (project of projects(); track project.id) {
                <mat-option [value]="project.id">{{ project.name }}</mat-option>
              }
              <mat-option [value]="newProject">New project…</mat-option>
            </mat-select>
            <mat-hint>Projects group related workflows.</mat-hint>
          </mat-form-field>
        }
        @if (form.controls.projectId.value === newProject) {
          <mat-form-field>
            <mat-label>Project name</mat-label>
            <input matInput formControlName="projectName" />
            <mat-hint>Projects group related workflows. You can rename it later.</mat-hint>
            @if (form.controls.projectName.hasError('duplicate')) {
              <mat-error>You already have a project with this name</mat-error>
            }
          </mat-form-field>
        }
        @if (template(); as chosen) {
          <p class="muted template-note">
            Creates {{ chosen.steps.length }} {{ chosen.steps.length === 1 ? 'step' : 'steps' }} as
            a draft. Suggested trigger: {{ chosen.trigger }}.
          </p>
        }
        @if (error(); as error) {
          <p class="page-error" role="alert">{{ error }}</p>
        }
        @if (created(); as workflow) {
          <a class="created-link" [routerLink]="['/workflows', workflow.id]">Open the workflow</a>
        }
        <div class="actions">
          <a mat-button routerLink="/workflows">Cancel</a>
          <button mat-flat-button type="submit" class="submit" [disabled]="saving()">
            Create workflow
          </button>
        </div>
      </section>
    </form>
  `,
})
export class WorkflowCreate {
  private readonly projectService = inject(ProjectService);
  private readonly workflows = inject(WorkflowService);
  private readonly templateService = inject(WorkflowTemplateService);
  private readonly router = inject(Router);
  private readonly query = inject(ActivatedRoute).snapshot.queryParamMap;

  protected readonly newProject = NEW_PROJECT;
  protected readonly templates = WORKFLOW_TEMPLATES;
  protected readonly template = signal<WorkflowTemplate | null>(
    templateById(this.query.get('template')),
  );
  protected readonly projects = signal<Project[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly created = signal<Workflow | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.template()?.name ?? '', [Validators.required, Validators.maxLength(100)]],
    description: [this.template()?.description ?? '', Validators.maxLength(2000)],
    projectId: [NEW_PROJECT],
    projectName: ['My workflows', [Validators.required, Validators.maxLength(100)]],
  });

  constructor() {
    this.projectService.list().subscribe({
      next: (projects) => {
        this.projects.set(projects);
        const requested = Number(this.query.get('projectId'));
        const preselected = projects.find((p) => p.id === requested) ?? projects[0];
        if (preselected) {
          this.form.controls.projectId.setValue(preselected.id);
        }
      },
      error: () =>
        this.error.set('Your projects could not be loaded. Refresh the page to try again.'),
    });
  }

  choose(template: WorkflowTemplate | null): void {
    const previous = this.template();
    const { name, description } = this.form.controls;
    if (name.value === (previous?.name ?? '')) {
      name.setValue(template?.name ?? '');
    }
    if (description.value === (previous?.description ?? '')) {
      description.setValue(template?.description ?? '');
    }
    this.template.set(template);
  }

  submit(): void {
    const creatingProject = this.form.controls.projectId.value === NEW_PROJECT;
    if (!creatingProject) {
      this.form.controls.projectName.disable();
    }
    const invalid = this.form.invalid;
    this.form.controls.projectName.enable();
    if (invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, description, projectId, projectName } = this.form.getRawValue();
    const template = this.template();

    this.saving.set(true);
    this.error.set(null);
    const project$: Observable<number> = creatingProject
      ? this.projectService.create({ name: projectName.trim(), description: null }).pipe(
          tap((project) => {
            this.projects.update((projects) => [...projects, project]);
            this.form.controls.projectId.setValue(project.id);
          }),
          map((project) => project.id),
        )
      : of(projectId);

    project$
      .pipe(
        concatMap((id) =>
          this.workflows.create(id, { name: name.trim(), description: description.trim() || null }),
        ),
        concatMap((workflow) => {
          this.created.set(workflow);
          return template
            ? this.templateService.addSteps(workflow.id, template).pipe(map(() => workflow))
            : of(workflow);
        }),
      )
      .subscribe({
        next: (workflow) => this.router.navigate(['/workflows', workflow.id]),
        error: (error: unknown) => this.failed(error),
      });
  }

  private failed(error: unknown): void {
    const problem = problemOf(error);
    if (this.created()) {
      this.error.set(
        'The workflow was created, but its example steps could not all be added. Open it to finish the setup.',
      );
      return;
    }
    this.saving.set(false);
    if (problem?.code === 'DUPLICATE_NAME' && this.form.controls.projectId.value === NEW_PROJECT) {
      this.form.controls.projectName.setErrors({ duplicate: true });
      this.form.controls.projectName.markAsTouched();
    } else if (problem?.code === 'DUPLICATE_NAME') {
      this.form.controls.name.setErrors({ duplicate: true });
      this.form.controls.name.markAsTouched();
    } else {
      this.error.set(problem?.detail ?? 'The workflow could not be created. Try again.');
    }
  }
}
