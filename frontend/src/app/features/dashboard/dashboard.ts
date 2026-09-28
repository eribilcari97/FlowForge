import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

import { StatusBadge } from '../../shared/status-badge';
import { formatInZone } from '../schedules/schedule-presets';
import { Dashboard as DashboardData, DashboardService } from './dashboard.service';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, DatePipe, MatButtonModule, MatCardModule, StatusBadge],
  styleUrl: './dashboard.scss',
  template: `
    <header class="page-header">
      <h1>Dashboard</h1>
      <a mat-button routerLink="/projects">Projects</a>
    </header>

    @if (error()) {
      <p class="page-error" role="alert">The dashboard could not be loaded.</p>
    }

    @if (dashboard(); as dashboard) {
      <div class="cards">
        <mat-card appearance="outlined" class="running">
          <mat-card-header>
            <mat-card-title>Running now</mat-card-title>
            <mat-card-subtitle>{{ dashboard.running.total }} running</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            @for (execution of dashboard.running.items; track execution.id) {
              <a class="row-link" [routerLink]="['/executions', execution.id]">
                <span>{{ execution.workflowName }} · run #{{ execution.runNumber }}</span>
                <span class="meta">since {{ execution.createdAt | date: 'shortTime' }}</span>
              </a>
            } @empty {
              <p class="empty">Nothing is running.</p>
            }
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined" class="failures">
          <mat-card-header>
            <mat-card-title>Failed in the last 24 hours</mat-card-title>
            <mat-card-subtitle>{{ dashboard.failedLast24Hours.total }} failed</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            @for (execution of dashboard.failedLast24Hours.items; track execution.id) {
              <a class="row-link" [routerLink]="['/executions', execution.id]">
                <span
                  >{{ execution.workflowName }} · run #{{ execution.runNumber }}
                  <app-status-badge [status]="execution.status"
                /></span>
                <span class="meta">{{ execution.errorSummary }}</span>
              </a>
            } @empty {
              <p class="empty">No failures. 🎉</p>
            }
          </mat-card-content>
          @if (dashboard.failedLast24Hours.total > dashboard.failedLast24Hours.items.length) {
            <mat-card-actions>
              <a mat-button routerLink="/executions">All executions</a>
            </mat-card-actions>
          }
        </mat-card>

        <mat-card appearance="outlined" class="upcoming">
          <mat-card-header>
            <mat-card-title>Upcoming scheduled runs</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            @for (run of dashboard.upcomingRuns; track run.scheduleId) {
              <a class="row-link" [routerLink]="['/workflows', run.workflowId]">
                <span>{{ run.workflowName }}</span>
                <span class="meta"
                  >{{ inZone(run.nextRunAt, run.timezone) }} ({{ run.timezone }}) ·
                  <code>{{ run.cronExpression }}</code></span
                >
              </a>
            } @empty {
              <p class="empty">No active schedules.</p>
            }
          </mat-card-content>
        </mat-card>
      </div>
      <p class="refresh-note">Refreshes every 10 seconds.</p>
    } @else if (!error()) {
      <p>Loading…</p>
    }
  `,
})
export class Dashboard {
  protected readonly dashboard = signal<DashboardData | null>(null);
  protected readonly error = signal(false);

  constructor() {
    inject(DashboardService)
      .watch()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (dashboard) => {
          this.error.set(false);
          this.dashboard.set(dashboard);
        },
        error: () => this.error.set(true),
      });
  }

  protected inZone(iso: string, timeZone: string): string {
    return formatInZone(iso, timeZone);
  }
}
