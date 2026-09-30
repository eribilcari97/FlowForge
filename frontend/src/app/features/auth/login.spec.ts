import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import type { MockInstance } from 'vitest';

import { Login } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let element: HTMLElement;
  let http: HttpTestingController;
  let navigateByUrl: MockInstance<Router['navigateByUrl']>;

  async function setUp(returnUrl: string | null) {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { queryParamMap: convertToParamMap(returnUrl ? { returnUrl } : {}) } },
    });
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Login);
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  async function submit(email: string, password: string) {
    fixture.componentInstance['form'].setValue({ email, password });
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('shows one generic message when the credentials are wrong', async () => {
    await setUp(null);

    await submit('ana@example.com', 'wrong-password');
    http
      .expectOne('/api/auth/login')
      .flush(
        { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Invalid email or password' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'Invalid email or password.',
    );
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('asks the user to wait after too many attempts', async () => {
    await setUp(null);

    await submit('ana@example.com', 'wrong-password');
    http
      .expectOne('/api/auth/login')
      .flush(
        { status: 429, code: 'TOO_MANY_REQUESTS', detail: 'Too many attempts.' },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await fixture.whenStable();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'Too many attempts. Wait a minute and try again.',
    );
  });

  it('returns to the page the user originally asked for', async () => {
    await setUp('/projects/42');

    await submit('ana@example.com', 'correct-horse-battery');
    http.expectOne('/api/auth/login').flush({
      accessToken: 'jwt-1',
      expiresIn: 3600,
      user: { id: 1, email: 'ana@example.com', displayName: 'Ana' },
    });

    expect(navigateByUrl).toHaveBeenCalledWith('/projects/42');
  });

  it('ignores a return URL pointing outside the app', async () => {
    await setUp('//evil.example.com');

    await submit('ana@example.com', 'correct-horse-battery');
    http.expectOne('/api/auth/login').flush({
      accessToken: 'jwt-1',
      expiresIn: 3600,
      user: { id: 1, email: 'ana@example.com', displayName: 'Ana' },
    });

    expect(navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('does not call the API while the form is invalid', async () => {
    await setUp(null);

    await submit('not-an-email', '');

    http.expectNone('/api/auth/login');
  });
});
