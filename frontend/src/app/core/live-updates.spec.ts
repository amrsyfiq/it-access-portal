import { HttpEventType, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { demoUserInterceptor } from './demo-user';
import { LiveEvent, LiveUpdatesService } from './live-updates';

describe('LiveUpdatesService', () => {
  let http: HttpTestingController;
  let service: LiveUpdatesService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([demoUserInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(LiveUpdatesService);
  });

  it('opens one stream through the normal interceptors and emits events as text arrives', () => {
    const received: LiveEvent[] = [];
    const first = service.events$.subscribe((e) => received.push(e));
    const second = service.events$.subscribe(); // shared: still one connection

    const stream = http.expectOne('/api/events/stream');
    expect(stream.request.headers.get('Accept')).toBe('text/event-stream');
    expect(stream.request.headers.get('X-Demo-User')).toBe('ted.l'); // EventSource could not do this
    expect(service.connected()).toBe(false);

    // The server writes to the open response; the browser reports everything received so far.
    let text = 'event:ready\ndata:{}\n\n';
    stream.event({ type: HttpEventType.DownloadProgress, loaded: text.length, partialText: text });
    expect(service.connected()).toBe(true);
    expect(received).toEqual([]);

    text += ':heartbeat\n\nevent:request\ndata:{"requestId":7,"eventType":"AccessRequestReviewed",';
    stream.event({ type: HttpEventType.DownloadProgress, loaded: text.length, partialText: text });
    expect(received).toEqual([]); // message not complete yet

    text += '"status":"APPROVED","actor":"alice.admin","occurredAt":"2026-10-14T08:00:00Z"}\n\n';
    text += 'event:notification\ndata:{"requestId":7,"eventId":"e1","processingStatus":"PROCESSED"}\n\n';
    stream.event({ type: HttpEventType.DownloadProgress, loaded: text.length, partialText: text });

    expect(received).toEqual([
      {
        type: 'request',
        data: {
          requestId: 7,
          eventType: 'AccessRequestReviewed',
          status: 'APPROVED',
          actor: 'alice.admin',
          occurredAt: '2026-10-14T08:00:00Z',
        },
      },
      { type: 'notification', data: { requestId: 7, eventId: 'e1', processingStatus: 'PROCESSED' } },
    ]);

    first.unsubscribe();
    second.unsubscribe();
  });
});
