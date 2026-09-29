import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { Project, ProjectService } from './project.service';

@Component({
  selector: 'app-project-list',
  imports: [RouterLink, MatButtonModule],
  styleUrl: './projects.scss',
  template: `
    <a class="crumb" routerLink="/workflows">Workflows</a>
    <header class="page-header">
      <div>
        <h1>Projects</h1>
        <p class="description">
          Projects group related workflows. Every workflow belongs to one project.
        </p>
      </div>
      <a mat-flat-button routerLink="/projects/new">New project</a>
    </header>

    @if (error()) {
      <p class="alert" role="alert">Projects could not be loaded. Refresh the page to try again.</p>
    } @else if (projects(); as projects) {
      @if (projects.length === 0) {
        <div class="panel blank">
          <h2>No projects yet</h2>
          <p class="description">
            Creating your first workflow sets up a project for it, or you can create one first.
          </p>
          <a mat-flat-button routerLink="/workflows/new">Create workflow</a>
        </div>
      } @else {
        <ul class="panel run-list">
          @for (project of projects; track project.id) {
            <li>
              <a class="run-row project-row" [routerLink]="['/projects', project.id]">
                <span class="run-title">{{ project.name }}</span>
                <span class="run-meta">{{ project.description || 'No description' }}</span>
                <span class="count"
                  >{{ project.workflowCount }}
                  {{ project.workflowCount === 1 ? 'workflow' : 'workflows' }}</span
                >
              </a>
            </li>
          }
        </ul>
      }
    } @else {
      <p class="muted">Loading projects…</p>
    }
  `,
})
export class ProjectList {
  protected readonly projects = signal<Project[] | null>(null);
  protected readonly error = signal(false);

  constructor() {
    inject(ProjectService)
      .list()
      .subscribe({
        next: (projects) => this.projects.set(projects),
        error: () => this.error.set(true),
      });
  }
}
