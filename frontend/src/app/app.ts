import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterOutlet } from '@angular/router';

import { HealthService } from './core/health/health.service';

/** Application shell: toolbar with the backend status, and the routed page below it. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, MatToolbarModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly backendStatus = toSignal(inject(HealthService).status());
}
