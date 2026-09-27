import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { RouterLink } from '@angular/router';

import { Project, ProjectService } from './project.service';

@Component({
  selector: 'app-project-list',
  imports: [RouterLink, MatButtonModule, MatListModule],
  styleUrl: './projects.scss',
  template: `
    <header class="page-header">
      <h1>Projects</h1>
      <a mat-flat-button routerLink="/projects/new">New project</a>
    </header>

    @if (error()) {
      <p class="page-error" role="alert">Projects could not be loaded.</p>
    } @else if (projects(); as projects) {
      @if (projects.length === 0) {
        <p class="empty">You don't have any projects yet. Create one to group your workflows.</p>
      } @else {
        <mat-nav-list>
          @for (project of projects; track project.id) {
            <a mat-list-item [routerLink]="['/projects', project.id]">
              <span matListItemTitle>{{ project.name }}</span>
              <span matListItemLine>{{ project.description || 'No description' }}</span>
            </a>
          }
        </mat-nav-list>
      }
    } @else {
      <p>Loading…</p>
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
