import { HttpClient, HttpDownloadProgressEvent, HttpEventType } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import {
  EMPTY,
  Observable,
  defer,
  filter,
  finalize,
  from,
  map,
  mergeMap,
  repeat,
  retry,
  share,
  tap,
  timer,
} from 'rxjs';
import { environment } from '../../environments/environment';
import { RequestStatus } from './models';
import { createSseParser } from './sse-parser';

/** A request was submitted or reviewed (pushed by the backend's Kafka live-update consumer). */
export interface RequestChange {
  requestId: number;
  eventType: 'AccessRequestSubmitted' | 'AccessRequestReviewed';
  status: RequestStatus;
  actor: string;
  occurredAt: string;
}

/** The notification consumer recorded (or failed) a notification. */
export interface NotificationChange {
  requestId: number;
  eventId: string;
  processingStatus: 'PROCESSED' | 'FAILED';
}

export type LiveEvent =
  | { type: 'request'; data: RequestChange }
  | { type: 'notification'; data: NotificationChange };

/**
 * One Server-Sent Events connection to GET {api}/events/stream, shared by every page that listens.
 *
 * Uses HttpClient (streaming progress events) instead of the browser's EventSource, because
 * EventSource cannot send headers: this way the demo-user headers / WSO2 Bearer token are added by
 * the usual interceptors. The connection opens when the first page subscribes, closes a few
 * seconds after the last one unsubscribes, and reconnects by itself when the server ends the stream
 * (every few minutes, by design) or the network drops.
 */
@Injectable({ providedIn: 'root' })
export class LiveUpdatesService {
  private readonly http = inject(HttpClient);

  /** True while the stream is open; shown as the "Live" badge in the header. */
  readonly connected = signal(false);

  readonly events$: Observable<LiveEvent> = defer(() => this.connect()).pipe(
    repeat({ delay: 1000 }), // server closed the stream normally: reconnect
    retry({ delay: (_err, attempt) => timer(Math.min(30_000, 2_000 * attempt)), resetOnSuccess: true }),
    share({ resetOnRefCountZero: () => timer(5_000) }), // survive page-to-page navigation
  );

  private connect(): Observable<LiveEvent> {
    const parse = createSseParser();
    let consumed = 0;
    return this.http
      .get(`${environment.apiBaseUrl}/events/stream`, {
        observe: 'events',
        responseType: 'text',
        reportProgress: true,
        headers: { Accept: 'text/event-stream' },
      })
      .pipe(
        mergeMap((event) => {
          if (event.type !== HttpEventType.DownloadProgress) return EMPTY;
          // partialText is everything received so far; parse only the new part.
          const text = (event as HttpDownloadProgressEvent).partialText ?? '';
          const chunk = text.slice(consumed);
          consumed = text.length;
          return from(parse(chunk));
        }),
        tap((message) => {
          if (message.event === 'ready') this.connected.set(true);
        }),
        filter((message) => message.event === 'request' || message.event === 'notification'),
        map((message) => ({ type: message.event, data: JSON.parse(message.data) }) as LiveEvent),
        finalize(() => this.connected.set(false)),
      );
  }
}
