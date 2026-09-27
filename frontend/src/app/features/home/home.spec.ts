import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Home } from './home';

describe('Home (foundation smoke test)', () => {
  let fixture: ComponentFixture<Home>;
  let element: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(Home);
    element = fixture.nativeElement;
    http = TestBed.inject(HttpTestingController);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('shows Connected and the response when /api/health answers', async () => {
    expect(element.textContent).toContain('FlowForge');
    expect(element.querySelector('.api-status')?.textContent).toContain('checking');

    http.expectOne('/api/health').flush({ status: 'UP', message: 'FlowForge backend is running' });
    await fixture.whenStable();

    expect(element.querySelector('.api-status')?.textContent).toContain('Backend: Connected');
    expect(element.querySelector('.api-response')?.textContent).toContain('FlowForge backend is running');
  });

  it('shows Not connected when /api/health fails', async () => {
    http.expectOne('/api/health').flush(null, { status: 504, statusText: 'Gateway Timeout' });
    await fixture.whenStable();

    expect(element.querySelector('.api-status')?.textContent).toContain('Backend: Not connected');
    expect(element.querySelector('.api-response')).toBeNull();
  });
});
