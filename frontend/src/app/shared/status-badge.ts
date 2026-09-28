import { Component, input } from '@angular/core';

@Component({
  selector: 'app-status-badge',
  template: `<span class="status-badge" [attr.data-status]="status()">{{ status() }}</span>`,
  styles: `
    .status-badge {
      font: var(--mat-sys-label-small);
      padding: 2px 8px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-highest);
      white-space: nowrap;
    }

    [data-status='RUNNING'],
    [data-status='READY'],
    [data-status='ACTIVE'] {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }

    [data-status='SUCCEEDED'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }

    [data-status='FAILED'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
  `,
})
export class StatusBadge {
  readonly status = input.required<string>();
}
