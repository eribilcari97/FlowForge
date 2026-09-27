import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App shell', () => {
  beforeEach(() => {
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
  });
});
