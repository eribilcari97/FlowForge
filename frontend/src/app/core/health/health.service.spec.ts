import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { BackendStatus, HealthService } from './health.service';

describe('HealthService', () => {
  let service: HealthService;
  let http: HttpTestingController;
  let result: BackendStatus | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HealthService);
    http = TestBed.inject(HttpTestingController);
    result = undefined;
    service.status().subscribe((status) => (result = status));
  });

  afterEach(() => http.verify());

  it('reports UP when the backend is healthy', () => {
    http.expectOne('/actuator/health').flush({ status: 'UP' });

    expect(result).toBe('UP');
  });

  it('reports DOWN when the backend answers 503 with status DOWN', () => {
    http
      .expectOne('/actuator/health')
      .flush({ status: 'DOWN' }, { status: 503, statusText: 'Service Unavailable' });

    expect(result).toBe('DOWN');
  });

  it('reports UNREACHABLE when the backend does not answer', () => {
    http.expectOne('/actuator/health').error(new ProgressEvent('error'));

    expect(result).toBe('UNREACHABLE');
  });
});
