import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { demoUserInterceptor } from '../core/demo-user';
import { AccessRequest, AccessRequestDetail } from '../core/models';
import { FakeLiveUpdates, provideFakeLiveUpdates } from './fake-live-updates';

/**
 * Real router + real HttpClient pipeline (incl. the demo-user interceptor), but HTTP calls are
 * captured by HttpTestingController instead of hitting the network. Tests answer them explicitly.
 */
export async function setUpApp(): Promise<{
  harness: RouterTestingHarness;
  http: HttpTestingController;
  live: FakeLiveUpdates;
}> {
  try {
    localStorage.clear();
  } catch {
    // ignore
  }
  const { provider, fake } = provideFakeLiveUpdates();
  TestBed.configureTestingModule({
    providers: [
      provider,
      provideRouter(routes),
      provideHttpClient(withInterceptors([demoUserInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  const harness = await RouterTestingHarness.create();
  return { harness, http: TestBed.inject(HttpTestingController), live: fake };
}

/** Lets signals/zoneless change detection settle and returns the rendered root element. */
export async function render(harness: RouterTestingHarness): Promise<HTMLElement> {
  await harness.fixture.whenStable();
  harness.detectChanges();
  return harness.routeNativeElement as HTMLElement;
}

export function aRequest(overrides: Partial<AccessRequest> = {}): AccessRequest {
  return {
    id: 7,
    requesterName: 'Ted L',
    systemName: 'GitLab',
    accessLevel: 'WRITE',
    reason: 'Need to push feature branches',
    status: 'PENDING',
    createdAt: '2026-10-13T05:52:58.249774Z',
    updatedAt: '2026-10-13T05:52:58.249774Z',
    ...overrides,
  };
}

export function aDetail(overrides: Partial<AccessRequestDetail> = {}): AccessRequestDetail {
  return {
    ...aRequest(overrides),
    history: [
      {
        id: 1,
        oldStatus: null,
        newStatus: 'PENDING',
        comment: null,
        changedAt: '2026-10-13T05:52:58.249774Z',
        changedBy: 'ted.l',
      },
    ],
    ...overrides,
  };
}
