import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from './auth.service';

const USER = { id: 1, email: 'ana@example.com', displayName: 'Ana' };

describe('AuthService', () => {
  let http: HttpTestingController;

  function createService(): AuthService {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('stores the token and user after a successful login', () => {
    const auth = createService();

    auth.login('ana@example.com', 'correct-horse-battery').subscribe();
    const request = http.expectOne('/api/auth/login');
    expect(request.request.body).toEqual({
      email: 'ana@example.com',
      password: 'correct-horse-battery',
    });
    request.flush({ accessToken: 'jwt-1', expiresIn: 3600, user: USER });

    expect(auth.token()).toBe('jwt-1');
    expect(auth.user()).toEqual(USER);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('keeps the session across page reloads until it expires', () => {
    localStorage.setItem(
      'flowforge.session',
      JSON.stringify({ token: 'stored', expiresAt: Date.now() + 60_000, user: USER }),
    );

    expect(createService().token()).toBe('stored');
  });

  it('ignores a stored session that has already expired', () => {
    localStorage.setItem(
      'flowforge.session',
      JSON.stringify({ token: 'old', expiresAt: Date.now() - 1, user: USER }),
    );

    const auth = createService();

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.user()).toBeNull();
  });

  it('treats the token as gone once its 60 minutes are over', () => {
    vi.useFakeTimers();
    const auth = createService();
    auth.login('ana@example.com', 'correct-horse-battery').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'jwt-1', expiresIn: 3600, user: USER });

    vi.advanceTimersByTime(3600 * 1000);

    expect(auth.token()).toBeNull();
    expect(auth.user()).toBeNull();
    expect(localStorage.getItem('flowforge.session')).toBeNull();
  });

  it('logs in right after registering', () => {
    const auth = createService();

    auth
      .register({ email: 'ana@example.com', password: 'correct-horse-battery', displayName: 'Ana' })
      .subscribe();
    http.expectOne('/api/auth/register').flush(USER, { status: 201, statusText: 'Created' });
    http.expectOne('/api/auth/login').flush({ accessToken: 'jwt-1', expiresIn: 3600, user: USER });

    expect(auth.token()).toBe('jwt-1');
  });

  it('forgets the session and goes to the login page on logout', () => {
    const auth = createService();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    auth.login('ana@example.com', 'correct-horse-battery').subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'jwt-1', expiresIn: 3600, user: USER });

    auth.logout('/projects/42');

    expect(auth.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('flowforge.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/projects/42' },
    });
  });
});
