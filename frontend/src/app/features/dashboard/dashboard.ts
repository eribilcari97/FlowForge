import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { clock } from '../../shared/clock';
import { elapsedMs, formatDuration, plural, relativeTime } from '../../shared/time';
import { formatInZone } from '../schedules/schedule-presets';
import { WorkflowCard } from '../workflows/workflow-card';
import { WorkflowOverviewService, Workspace } from '../workflows/workflow-overview.service';
import { Welcome } from '../workflows/welcome';
import { Dashboard as DashboardData, DashboardService } from './dashboard.service';

const ROSTER_SIZE = 6;

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatButtonModule, WorkflowCard, Welcome],
  styleUrl: './dashboard.scss',
  template: `
    @if (workspace()?.workflows?.length === 0) {
      <app-welcome />
    } @else {
      <header class="page-header">
        <div>
          <h1>Workspace</h1>
          @if (dashboard(); as dashboard) {
            <p class="description pulse-line">{{ pulse(dashboard) }}</p>
          }
        </div>
        <a mat-flat-button routerLink="/workflows/new">New workflow</a>
      </header>

      @if (error()) {
        <p class="alert" role="alert">
          The live overview could not be loaded. It will try again in a few seconds.
        </p>
      }

      @if (dashboard(); as dashboard) {
        <div class="ops">
          <section class="lane running" data-status="RUNNING" aria-labelledby="running-title">
            <header>
              <h2 id="running-title">Running now</h2>
              <span class="total">{{ dashboard.running.total }} running</span>
            </header>
            <div class="run-list">
              @for (execution of dashboard.running.items; track execution.id) {
                <a
                  class="run-row"
                  data-status="RUNNING"
                  [routerLink]="['/executions', execution.id]"
                >
                  <span class="run-title"
                    >{{ execution.workflowName }} · run #{{ execution.runNumber }}</span
                  >
                  <span class="run-meta"
                    >Running for {{ elapsed(execution.createdAt) }},
                    {{
                      execution.triggerType === 'SCHEDULE'
                        ? 'started by schedule'
                        : 'started manually'
                    }}</span
                  >
                </a>
              } @empty {
                <p class="empty">Nothing is running.</p>
              }
            </div>
          </section>

          <section class="lane failures" data-status="FAILED" aria-labelledby="failures-title">
            <header>
              <h2 id="failures-title">Needs attention</h2>
              <span class="total">{{ dashboard.failedLast24Hours.total }} failed</span>
            </header>
            <div class="run-list">
              @for (execution of dashboard.failedLast24Hours.items; track execution.id) {
                <a
                  class="run-row"
                  data-status="FAILED"
                  [routerLink]="['/executions', execution.id]"
                >
                  <span class="run-title"
                    >{{ execution.workflowName }} · run #{{ execution.runNumber }}</span
                  >
                  <span class="run-meta error">{{ execution.errorSummary }}</span>
                  <span class="run-meta"
                    >Failed {{ relative(execution.finishedAt ?? execution.createdAt) }}</span
                  >
                </a>
              } @empty {
                <p class="empty">No failed runs in the last 24 hours.</p>
              }
            </div>
            @if (dashboard.failedLast24Hours.total > dashboard.failedLast24Hours.items.length) {
              <a class="more" routerLink="/executions">See all runs</a>
            }
          </section>

          <section class="lane upcoming" aria-labelledby="upcoming-title">
            <header>
              <h2 id="upcoming-title">Coming up</h2>
            </header>
            <ol class="timeline">
              @for (run of dashboard.upcomingRuns; track run.scheduleId + run.nextRunAt) {
                <li>
                  <span class="when">{{ relative(run.nextRunAt) }}</span>
                  <a [routerLink]="['/workflows', run.workflowId]">{{ run.workflowName }}</a>
                  <span class="run-meta"
                    >{{ inZone(run.nextRunAt, run.timezone) }} {{ run.timezone }}
                    <code>{{ run.cronExpression }}</code></span
                  >
                </li>
              } @empty {
                <li class="empty">
                  No scheduled runs. Add a schedule to an active workflow to run it automatically.
                </li>
              }
            </ol>
          </section>
        </div>
      } @else if (!error()) {
        <p class="muted">Loading the workspace…</p>
      }

      @if (roster().length > 0) {
        <section aria-labelledby="roster-title">
          <header class="section-header">
            <h2 id="roster-title">Recently active workflows</h2>
            <a routerLink="/workflows">All {{ workspace()!.workflows.length }} workflows</a>
          </header>
          <ul class="card-grid">
            @for (overview of roster(); track overview.workflow.id) {
              <li><app-workflow-card [overview]="overview" [now]="now()" /></li>
            }
          </ul>
        </section>
      }
    }
  `,
})
export class Dashboard {
  protected readonly dashboard = signal<DashboardData | null>(null);
  protected readonly workspace = signal<Workspace | null>(null);
  protected readonly error = signal(false);
  protected readonly now = clock();

  protected readonly roster = computed(() => {
    const lastActivity = (o: Workspace['workflows'][number]) =>
      o.recentRuns[0]?.createdAt ?? o.workflow.updatedAt;
    return [...(this.workspace()?.workflows ?? [])]
      .sort((a, b) => lastActivity(b).localeCompare(lastActivity(a)))
      .slice(0, ROSTER_SIZE);
  });

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
    inject(WorkflowOverviewService)
      .load()
      .pipe(takeUntilDestroyed())
      .subscribe({ next: (workspace) => this.workspace.set(workspace), error: () => undefined });
  }

  protected pulse(dashboard: DashboardData): string {
    const running = dashboard.running.total;
    const failed = dashboard.failedLast24Hours.total;
    const parts = [
      running === 0 ? 'Nothing is running' : `${plural(running, 'run')} in progress`,
      failed === 0 ? 'no failures in the last 24 hours' : `${failed} failed in the last 24 hours`,
    ];
    const next = dashboard.upcomingRuns[0];
    if (next) {
      parts.push(`next scheduled run ${relativeTime(next.nextRunAt, this.now())}`);
    }
    return parts.join(', ') + '.';
  }

  protected elapsed(iso: string): string {
    return formatDuration(elapsedMs(iso, null, this.now()) ?? 0);
  }

  protected relative(iso: string): string {
    return relativeTime(iso, this.now());
  }

  protected inZone(iso: string, timeZone: string): string {
    return formatInZone(iso, timeZone);
  }
}
