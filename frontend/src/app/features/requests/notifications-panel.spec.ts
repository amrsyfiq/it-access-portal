import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NotificationLogEntry } from '../../core/models';
import { FakeLiveUpdates, provideFakeLiveUpdates } from '../../testing/fake-live-updates';
import { NotificationsPanel } from './notifications-panel';

describe('NotificationsPanel', () => {
  let fixture: ComponentFixture<NotificationsPanel>;
  let http: HttpTestingController;
  let live: FakeLiveUpdates;

  beforeEach(async () => {
    const fake = provideFakeLiveUpdates();
    live = fake.fake;
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), fake.provider] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotificationsPanel);
    fixture.componentRef.setInput('requestId', 7);
    fixture.componentRef.setInput('reloadKey', 'PENDING');
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const el = () => fixture.nativeElement as HTMLElement;

  const entry = (overrides: Partial<NotificationLogEntry>): NotificationLogEntry => ({
    eventId: 'b0da3bb3-25af-46e2-9942-ad2b2dde32a4',
    eventType: 'AccessRequestSubmitted',
    processingStatus: 'PROCESSED',
    createdAt: '2026-10-13T08:59:40Z',
    processedAt: '2026-10-13T08:59:40Z',
    errorMessage: null,
    ...overrides,
  });

  it('lists processed and failed notifications with the error message', async () => {
    http.expectOne('/api/requests/7/notifications').flush([
      entry({}),
      entry({
        eventId: '296b3957-d58f-4758-8615-d0989a2cea65',
        eventType: 'AccessRequestReviewed',
        processingStatus: 'FAILED',
        processedAt: null,
        errorMessage: "Simulated notification failure for system 'Legacy Mainframe'",
      }),
    ]);
    await fixture.whenStable();

    const items = el().querySelectorAll('li');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Submitted');
    expect(items[0].textContent).toContain('PROCESSED');
    expect(items[1].classList).toContain('failed');
    expect(items[1].textContent).toContain('Simulated notification failure');
  });

  it('explains the empty state and reloads on Refresh', async () => {
    http.expectOne('/api/requests/7/notifications').flush([]);
    await fixture.whenStable();
    expect(el().textContent).toContain('No notifications yet');

    (el().querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    http.expectOne('/api/requests/7/notifications').flush([entry({})]);
    await fixture.whenStable();
    expect(el().querySelectorAll('li').length).toBe(1);
  });

  it('reloads when the request status changes', async () => {
    http.expectOne('/api/requests/7/notifications').flush([entry({})]);
    fixture.componentRef.setInput('reloadKey', 'APPROVED');
    await fixture.whenStable();

    http.expectOne('/api/requests/7/notifications').flush([entry({}), entry({ eventId: 'x2', eventType: 'AccessRequestReviewed' })]);
    await fixture.whenStable();
    expect(el().querySelectorAll('li').length).toBe(2);
  });

  it('reloads by itself when a notification for this request is recorded (live update)', async () => {
    http.expectOne('/api/requests/7/notifications').flush([entry({})]);
    await fixture.whenStable();

    // an event about another request is ignored
    live.events.next({ type: 'notification', data: { requestId: 8, eventId: 'x', processingStatus: 'PROCESSED' } });
    http.expectNone('/api/requests/7/notifications');

    live.events.next({ type: 'notification', data: { requestId: 7, eventId: 'x2', processingStatus: 'PROCESSED' } });
    http.expectOne('/api/requests/7/notifications').flush([entry({}), entry({ eventId: 'x2', eventType: 'AccessRequestReviewed' })]);
    await fixture.whenStable();
    expect(el().querySelectorAll('li').length).toBe(2);
    expect(el().querySelector('.spinner')).toBeNull(); // quiet reload: no loading state shown
  });
});
