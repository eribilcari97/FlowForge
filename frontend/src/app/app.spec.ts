import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App shell', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('shows the application name and the backend status', async () => {
    const fixture = TestBed.createComponent(App);
    const element: HTMLElement = fixture.nativeElement;
    await fixture.whenStable();

    expect(element.querySelector('.brand')?.textContent).toContain('FlowForge');
    expect(element.querySelector('.backend-status')?.textContent).toContain('checking');

    TestBed.inject(HttpTestingController).expectOne('/actuator/health').flush({ status: 'UP' });
    await fixture.whenStable();

    expect(element.querySelector('.backend-status')?.textContent).toContain('Backend: UP');
    expect(element.querySelector('.logout')).toBeNull();
  });

  it('shows the logged-in user and a logout button', async () => {
    localStorage.setItem(
      'flowforge.session',
      JSON.stringify({
        token: 'jwt-1',
        expiresAt: Date.now() + 60_000,
        user: { id: 1, email: 'ana@example.com', displayName: 'Ana' },
      }),
    );
    const fixture = TestBed.createComponent(App);
    const element: HTMLElement = fixture.nativeElement;
    await fixture.whenStable();
    TestBed.inject(HttpTestingController).expectOne('/actuator/health').flush({ status: 'UP' });
    await fixture.whenStable();

    expect(element.querySelector('.user')?.textContent).toContain('Ana');
    expect(element.querySelector('.logout')).not.toBeNull();
  });
});
