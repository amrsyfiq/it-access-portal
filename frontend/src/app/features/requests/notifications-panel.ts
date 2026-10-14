import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
import { NotificationLogEntry } from '../../core/models';

type PanelState =
  | { kind: 'loading' }
  | { kind: 'loaded'; entries: NotificationLogEntry[] }
  | { kind: 'error'; error: ApiError };

/**
 * Shows NOTIFICATION_LOG rows for a request: proof that Kafka events were consumed.
 * Processing is asynchronous, so a row can appear a moment after the change; "Refresh" re-reads.
 */
@Component({
  selector: 'app-notifications-panel',
  imports: [DatePipe],
  template: `
    <section class="card notifications" aria-labelledby="notifications-heading">
      <div class="head">
        <h2 id="notifications-heading">Notifications</h2>
        <button class="btn btn-small" type="button" (click)="refresh()">Refresh</button>
      </div>
      <p class="muted hint">Recorded asynchronously by the Kafka consumer.</p>
      @let s = state();
      @if (s.kind === 'loading') {
        <p class="muted"><span class="spinner"></span> Loading…</p>
      } @else if (s.kind === 'error') {
        <p class="error-text" role="alert">{{ s.error.message }}</p>
      } @else {
        <ul>
          @for (n of s.entries; track n.eventId) {
            <li [class.failed]="n.processingStatus === 'FAILED'">
              <div class="row">
                <span class="type">{{ label(n) }}</span>
                <span class="status">{{ n.processingStatus }}</span>
              </div>
              <span class="muted small">
                {{ (n.processedAt ?? n.createdAt) | date: 'medium' }} · event {{ n.eventId.slice(0, 8) }}
              </span>
              @if (n.errorMessage) {
                <p class="error-text">{{ n.errorMessage }}</p>
              }
            </li>
          } @empty {
            <li class="muted">No notifications yet. Processing is asynchronous; try Refresh.</li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .notifications { margin-top: 16px; }
    .head { display: flex; justify-content: space-between; align-items: center; }
    .head h2 { margin: 0; }
    .hint { font-size: 0.8rem; margin: 2px 0 10px; }
    .btn-small { padding: 4px 10px; font-size: 0.8rem; }
    ul { list-style: none; margin: 0; padding: 0; }
    li { padding: 8px 0; border-top: 1px solid var(--border); }
    .row { display: flex; justify-content: space-between; gap: 8px; }
    .type { font-weight: 600; }
    .status { font-size: 0.75rem; font-weight: 700; color: var(--success); }
    li.failed .status { color: var(--danger); }
    .small { font-size: 0.8rem; }
    .spinner { width: 14px; height: 14px; border-width: 2px; }
  `,
})
export class NotificationsPanel {
  private readonly api = inject(AccessRequestApi);

  readonly requestId = input.required<number>();
  /** Any value that changes when the request changes (e.g. its status) triggers a reload. */
  readonly reloadKey = input<unknown>();

  protected readonly state = signal<PanelState>({ kind: 'loading' });
  private readonly refresh$ = new Subject<void>();

  constructor() {
    const params = computed(() => ({ id: this.requestId(), key: this.reloadKey() }));
    combineLatest([toObservable(params), this.refresh$.pipe(startWith(undefined))])
      .pipe(
        switchMap(([{ id }]) =>
          this.api.notifications(id).pipe(
            map((entries): PanelState => ({ kind: 'loaded', entries })),
            catchError((err) => of<PanelState>({ kind: 'error', error: toApiError(err) })),
            startWith<PanelState>({ kind: 'loading' }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((s) => this.state.set(s));
  }

  protected refresh(): void {
    this.refresh$.next();
  }

  protected label(n: NotificationLogEntry): string {
    return n.eventType === 'AccessRequestSubmitted' ? 'Submitted' : 'Reviewed';
  }
}
