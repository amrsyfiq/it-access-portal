import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, combineLatest, filter, map, of, startWith, switchMap } from 'rxjs';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
import { LiveUpdatesService } from '../../core/live-updates';
import { SessionService } from '../../core/session';
import { AccessRequestDetail } from '../../core/models';
import { StatusBadge } from '../../shared/status-badge';
import { NotificationsPanel } from './notifications-panel';
import { ReviewPanel } from './review-panel';

type DetailState =
  | { kind: 'loading' }
  | { kind: 'loaded'; request: AccessRequestDetail }
  | { kind: 'error'; error: ApiError };

@Component({
  selector: 'app-request-detail',
  imports: [RouterLink, DatePipe, StatusBadge, ReviewPanel, NotificationsPanel],
  templateUrl: './request-detail.html',
  styleUrl: './request-detail.css',
})
export class RequestDetail {
  private readonly api = inject(AccessRequestApi);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(SessionService);

  protected readonly state = signal<DetailState>({ kind: 'loading' });
  /** One-off success message, e.g. passed by the form via router state after creating the request. */
  protected readonly flash = signal<string | null>(
    (inject(Router).currentNavigation()?.extras.state?.['flash'] as string | undefined) ?? null,
  );

  /** Approve/reject are only offered to admins, and only while the request is still PENDING. */
  protected readonly canReview = computed(() => {
    const s = this.state();
    return this.session.isAdmin() && s.kind === 'loaded' && s.request.status === 'PENDING';
  });

  private readonly reload$ = new Subject<void>();

  constructor() {
    const id$ = this.route.paramMap.pipe(map((params) => Number(params.get('id'))));
    const currentId = toSignal(id$);
    combineLatest([id$, this.reload$.pipe(startWith(undefined))])
      .pipe(
        switchMap(([id]) =>
          this.api.get(id).pipe(
            map((request): DetailState => ({ kind: 'loaded', request })),
            catchError((err) => of<DetailState>({ kind: 'error', error: toApiError(err) })),
            startWith<DetailState>({ kind: 'loading' }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));

    // Live updates: someone changed THIS request (e.g. an admin approved it while the employee is
    // looking at it). Reload it quietly, without the loading spinner.
    inject(LiveUpdatesService)
      .events$.pipe(
        filter((e) => e.type === 'request' && e.data.requestId === currentId()),
        switchMap(() => this.api.get(currentId()!).pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((request) => {
        const before = this.state();
        this.state.set({ kind: 'loaded', request });
        // Only announce changes made elsewhere: our own approve/reject already updated the page.
        if (before.kind === 'loaded' && before.request.status !== request.status) {
          this.flash.set(`Updated live: this request was ${request.status.toLowerCase()}.`);
        }
      });
  }

  protected reload(): void {
    this.reload$.next();
  }

  /** The PATCH response already contains the updated request + history, so no extra GET is needed. */
  protected onReviewed(request: AccessRequestDetail): void {
    this.state.set({ kind: 'loaded', request });
    this.flash.set(`Request ${request.status.toLowerCase()}.`);
  }
}
