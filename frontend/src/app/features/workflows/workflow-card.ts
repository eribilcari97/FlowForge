import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { relativeTime } from '../../shared/time';
import { ExecutionSummary } from '../executions/execution.service';
import { WorkflowOverview } from './workflow-overview.service';
import { LAST_RUN_LABELS, runSummary, triggerSummary } from './workflow-state';

const STATUS_LABELS = { ACTIVE: 'Active', DRAFT: 'Inactive', ARCHIVED: 'Archived' } as const;

@Component({
  selector: 'app-workflow-card',
  imports: [RouterLink],
  template: `
    @let w = overview().workflow;
    <article class="card">
      <h3>{{ w.name }}</h3>
      <p class="desc" [class.missing]="!w.description">
        {{ w.description || 'No description yet.' }}
      </p>

      <div class="state">
        <p class="status" [attr.data-state]="w.status">{{ statusLabel() }}</p>
        <p class="trigger">{{ trigger() }}</p>
      </div>

      <div class="history">
        @if (lastRun(); as run) {
          <p class="last-run">
            <span class="muted">Last run:</span> {{ ago(run.createdAt) }}
            <span class="sep" aria-hidden="true">·</span>&ngsp;<span
              class="result"
              [attr.data-status]="run.status"
              >{{ resultLabel(run) }}</span
            >
          </p>
          <p class="totals">{{ totals() }}</p>
        } @else {
          <p class="last-run muted">Not run yet</p>
        }
      </div>

      <a class="view" [routerLink]="['/workflows', w.id]"
        >View workflow<span class="sr-only">: {{ w.name }}</span>
        <span class="arrow" aria-hidden="true">→</span></a
      >
    </article>
  `,
  styles: `
    .card {
      position: relative;
      display: flex;
      flex-direction: column;
      height: 100%;
      box-sizing: border-box;
      padding: 20px 20px 16px;
      background: var(--ff-panel);
      border: 1px solid var(--ff-rule);
      border-radius: var(--ff-radius);

      &:hover {
        border-color: var(--ff-ink);
      }

      &:focus-within {
        outline: 2px solid var(--ff-run);
        outline-offset: 2px;
      }
    }

    p {
      margin: 0;
    }

    h3 {
      font-size: 1.125rem;
      font-weight: 700;
      letter-spacing: -0.01em;
      line-height: 1.25;
    }

    .desc {
      margin-top: 6px;
      color: var(--ff-ink-2);
      font-size: 0.875rem;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;

      &.missing {
        font-style: italic;
      }
    }

    .state,
    .history {
      display: flex;
      flex-direction: column;
      gap: 3px;
      font-size: 0.875rem;
    }

    .state {
      margin-top: 16px;
    }

    .status {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--ff-ink-2);
      font-weight: 600;

      &::before {
        content: '';
        width: 8px;
        height: 8px;
        border-radius: 50%;
        box-shadow: inset 0 0 0 1.5px var(--ff-idle);
      }

      &[data-state='ACTIVE'] {
        color: var(--ff-ok-ink);

        &::before {
          background: var(--ff-ok);
          box-shadow: none;
        }
      }
    }

    .trigger {
      padding-left: 16px;
    }

    .history {
      margin-top: 16px;
      padding-top: 14px;
      border-top: 1px solid var(--ff-rule);
    }

    .muted,
    .sep,
    .totals {
      color: var(--ff-ink-2);
    }

    .totals {
      font-size: 0.8125rem;
    }

    .result {
      font-weight: 600;
      color: var(--status-ink);
    }

    .result[data-status='SUCCEEDED']::before {
      content: '✓ ' / '';
    }

    .result[data-status='FAILED']::before {
      content: '✕ ' / '';
    }

    .result[data-status='RUNNING']::before {
      content: '● ' / '';
      color: var(--ff-run);
    }

    .view {
      align-self: flex-start;
      margin-top: auto;
      padding-top: 18px;
      font-size: 0.875rem;
      font-weight: 700;
      text-decoration: none;

      &::after {
        content: '';
        position: absolute;
        inset: 0;
        border-radius: inherit;
      }

      &:focus-visible {
        outline: none;
      }
    }

    .arrow {
      display: inline-block;
      margin-left: 2px;
      transition: transform 120ms ease-out;
    }

    .card:hover .arrow {
      transform: translateX(3px);
    }

    .card:hover .view {
      text-decoration: underline;
      text-underline-offset: 3px;
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
    }
  `,
})
export class WorkflowCard {
  readonly overview = input.required<WorkflowOverview>();
  readonly now = input.required<number>();

  protected readonly lastRun = computed<ExecutionSummary | null>(
    () => this.overview().recentRuns[0] ?? null,
  );
  protected readonly statusLabel = computed(() => STATUS_LABELS[this.overview().workflow.status]);
  protected readonly trigger = computed(() => {
    const { workflow, schedules } = this.overview();
    const { label } = triggerSummary(workflow.status, schedules, this.now());
    const waiting = workflow.status !== 'ACTIVE' && schedules.some((s) => s.enabled);
    return waiting ? `${label}, once active` : label;
  });
  protected readonly totals = computed(() => {
    const { total, failures } = runSummary(this.overview().recentRuns, this.overview().totalRuns);
    return `${total} · ${failures}`;
  });

  protected ago(iso: string): string {
    return relativeTime(iso, this.now());
  }

  protected resultLabel(run: ExecutionSummary): string {
    return LAST_RUN_LABELS[run.status];
  }
}
