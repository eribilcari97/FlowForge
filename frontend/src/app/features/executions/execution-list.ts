import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';

import { clock } from '../../shared/clock';
import { StatusBadge } from '../../shared/status-badge';
import { elapsedMs, formatDuration, relativeTime } from '../../shared/time';
import { ExecutionService, ExecutionStatus, ExecutionSummary, Page } from './execution.service';

@Component({
  selector: 'app-execution-list',
  imports: [
    RouterLink,
    DatePipe,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    MatPaginatorModule,
    StatusBadge,
  ],
  styleUrl: './executions.scss',
  template: `
    <header class="page-header">
      <div>
        <h1>Runs</h1>
        <p class="description">Every run of every workflow, newest first.</p>
      </div>
      <mat-form-field class="status-filter" subscriptSizing="dynamic">
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
      <p class="alert" role="alert">Runs could not be loaded. Refresh the page to try again.</p>
    } @else if (result(); as result) {
      @if (result.items.length === 0) {
        <div class="panel blank">
          <p class="empty">
            {{
              status()
                ? 'No runs with this status.'
                : 'No runs yet. Activate a workflow and start it, or give it a schedule.'
            }}
          </p>
          @if (!status()) {
            <a mat-stroked-button routerLink="/workflows">Go to workflows</a>
          }
        </div>
      } @else {
        <div class="table-scroll">
          <table class="executions">
            <thead>
              <tr>
                <th>Workflow</th>
                <th>Run</th>
                <th>Status</th>
                <th>Trigger</th>
                <th>Started</th>
                <th>Duration</th>
              </tr>
            </thead>
            <tbody>
              @for (execution of result.items; track execution.id) {
                <tr>
                  <td class="rail" [attr.data-status]="execution.status">
                    <a [routerLink]="['/executions', execution.id]">{{ execution.workflowName }}</a>
                  </td>
                  <td>#{{ execution.runNumber }}</td>
                  <td><app-status-badge [status]="execution.status" /></td>
                  <td>{{ execution.triggerType === 'SCHEDULE' ? 'Schedule' : 'Manual' }}</td>
                  <td>
                    {{ execution.createdAt | date: 'MMM d, HH:mm:ss' }}
                    <span class="muted ago">{{ relative(execution.createdAt) }}</span>
                  </td>
                  <td class="job-duration">{{ duration(execution) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <mat-paginator
          [length]="result.total"
          [pageIndex]="result.page"
          [pageSize]="result.size"
          [pageSizeOptions]="[10, 20, 50]"
          (page)="changePage($event)"
        />
      }
    } @else {
      <p class="muted">Loading runs…</p>
    }
  `,
})
export class ExecutionList {
  private readonly executions = inject(ExecutionService);

  protected readonly statuses: ExecutionStatus[] = ['RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
  protected readonly status = signal<ExecutionStatus | null>(null);
  protected readonly result = signal<Page<ExecutionSummary> | null>(null);
  protected readonly error = signal(false);
  protected readonly now = clock();
  private page = 0;
  private size = 20;

  constructor() {
    this.load();
  }

  protected relative(iso: string): string {
    return relativeTime(iso, this.now());
  }

  protected duration(execution: ExecutionSummary): string {
    const ms = elapsedMs(execution.createdAt, execution.finishedAt, this.now()) ?? 0;
    return execution.finishedAt ? formatDuration(ms) : `${formatDuration(ms)} so far`;
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
