import { Component, input } from '@angular/core';

@Component({
  selector: 'app-status-badge',
  template: `<span class="status-badge" [attr.data-status]="status()"
    ><span class="lamp" aria-hidden="true"></span><span class="label">{{ status() }}</span></span
  >`,
  styles: `
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 1px 8px 1px 6px;
      border-radius: 4px;
      background: var(--status-wash);
      color: var(--status-ink);
      font-size: 0.75rem;
      font-weight: 600;
      line-height: 1.5;
      white-space: nowrap;
      vertical-align: middle;
    }

    .lamp {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--status);
    }

    [data-status='RUNNING'] .lamp {
      animation: ff-glow 1.6s ease-in-out infinite;
    }

    [data-status='CANCELLED'] .lamp,
    [data-status='SKIPPED'] .lamp,
    [data-status='PAUSED'] .lamp {
      background: transparent;
      box-shadow: inset 0 0 0 1.5px var(--status);
    }

    .label {
      display: inline-block;
      text-transform: lowercase;
    }

    .label::first-letter {
      text-transform: uppercase;
    }
  `,
})
export class StatusBadge {
  readonly status = input.required<string>();
}
