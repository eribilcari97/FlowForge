import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  function logIn(): void {
    auth.login('ana@example.com', 'correct-horse-battery').subscribe();
    backend.expectOne('/api/auth/login').flush({
      accessToken: 'jwt-1',
      expiresIn: 3600,
      user: { id: 1, email: 'ana@example.com', displayName: 'Ana' },
    });
  }

  it('adds the bearer token to API requests', () => {
    logIn();

    http.get('/api/projects').subscribe();

    expect(backend.expectOne('/api/projects').request.headers.get('Authorization')).toBe(
      'Bearer jwt-1',
    );
  });

  it('sends no Authorization header when nobody is logged in', () => {
    http.get('/api/projects').subscribe();

    expect(backend.expectOne('/api/projects').request.headers.has('Authorization')).toBe(false);
  });

  it('never sends the token to other hosts or non-API paths', () => {
    logIn();

    http.get('https://example.com/api/projects').subscribe();
    http.get('/actuator/health').subscribe();

    expect(
      backend.expectOne('https://example.com/api/projects').request.headers.has('Authorization'),
    ).toBe(false);
    expect(backend.expectOne('/actuator/health').request.headers.has('Authorization')).toBe(false);
  });

  it('logs out and returns to the current page later when the API answers 401', () => {
    logIn();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/projects/42');
    let failed = false;

    http.get('/api/projects').subscribe({ error: () => (failed = true) });
    backend
      .expectOne('/api/projects')
      .flush({ status: 401, code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/projects/42' },
    });
  });

  it('does not treat a failed login (401) as an expired session', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

    auth.login('ana@example.com', 'wrong').subscribe({ error: () => undefined });
    backend
      .expectOne('/api/auth/login')
      .flush(
        { status: 401, code: 'INVALID_CREDENTIALS' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(navigate).not.toHaveBeenCalled();
  });
});
