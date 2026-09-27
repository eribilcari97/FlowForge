import { JsonPipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatCardModule } from '@angular/material/card';

import { HealthService } from '../../core/health/health.service';

/** Foundation smoke-test page: shows whether `/api` reaches the backend. Replaced by real pages in later sections. */
@Component({
  selector: 'app-home',
  imports: [MatCardModule, JsonPipe],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title>FlowForge</mat-card-title>
        <mat-card-subtitle>Foundation smoke test</mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        @if (check(); as check) {
          @if (check.connected) {
            <p class="api-status">Backend: Connected</p>
            <pre class="api-response">{{ check.response | json }}</pre>
          } @else {
            <p class="api-status">Backend: Not connected</p>
          }
        } @else {
          <p class="api-status">Backend: checking…</p>
        }
      </mat-card-content>
    </mat-card>
  `,
})
export class Home {
  protected readonly check = toSignal(inject(HealthService).apiHealth());
}
