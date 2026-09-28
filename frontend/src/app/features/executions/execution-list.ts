import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';

import { StatusBadge } from '../../shared/status-badge';
import { ExecutionService, ExecutionStatus, ExecutionSummary, Page } from './execution.service';

@Component({
  selector: 'app-execution-list',
  imports: [
    RouterLink,
    DatePipe,
    MatFormFieldModule,
    MatSelectModule,
    MatPaginatorModule,
    StatusBadge,
  ],
  styleUrl: './executions.scss',
  template: `
    <header class="page-header">
      <h1>Executions</h1>
      <mat-form-field class="status-filter">
        <mat-label>Status</mat-label>
        <mat-select [value]="status()" (valueChange)="filter($event)">
          <mat-option [value]="null">All</mat-option>
          @for (option of statuses; track option) {
            <mat-option [value]="option">{{ option }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </header>

    @if (error()) {
      <p class="page-error" role="alert">Executions could not be loaded.</p>
    } @else if (result(); as result) {
      @if (result.items.length === 0) {
        <p class="empty">No executions yet. Run an active workflow to see it here.</p>
      } @else {
        <table class="executions">
          <thead>
            <tr>
              <th>Workflow</th>
              <th>Run</th>
              <th>Status</th>
              <th>Started</th>
              <th>Finished</th>
            </tr>
          </thead>
          <tbody>
            @for (execution of result.items; track execution.id) {
              <tr>
                <td>
                  <a [routerLink]="['/executions', execution.id]">{{ execution.workflowName }}</a>
                </td>
                <td>#{{ execution.runNumber }}</td>
                <td><app-status-badge [status]="execution.status" /></td>
                <td>{{ execution.createdAt | date: 'medium' }}</td>
                <td>{{ execution.finishedAt ? (execution.finishedAt | date: 'medium') : '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
        <mat-paginator
          [length]="result.total"
          [pageIndex]="result.page"
          [pageSize]="result.size"
          [pageSizeOptions]="[10, 20, 50]"
          (page)="changePage($event)"
        />
      }
    } @else {
      <p>Loading…</p>
    }
  `,
})
export class ExecutionList {
  private readonly executions = inject(ExecutionService);

  protected readonly statuses: ExecutionStatus[] = ['RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
  protected readonly status = signal<ExecutionStatus | null>(null);
  protected readonly result = signal<Page<ExecutionSummary> | null>(null);
  protected readonly error = signal(false);
  private page = 0;
  private size = 20;

  constructor() {
    this.load();
  }

  filter(status: ExecutionStatus | null): void {
    this.status.set(status);
    this.page = 0;
    this.load();
  }

  changePage(event: PageEvent): void {
    this.page = event.pageIndex;
    this.size = event.pageSize;
    this.load();
  }

  private load(): void {
    this.error.set(false);
    this.executions.list(this.status(), this.page, this.size).subscribe({
      next: (result) => this.result.set(result),
      error: () => this.error.set(true),
    });
  }
}
