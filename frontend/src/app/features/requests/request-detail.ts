import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
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
  }

  protected reload(): void {
    this.reload$.next();
  }

  /** The PATCH response already contains the updated request + history, so no extra GET is needed. */
  protected onReviewed(request: AccessRequestDetail): void {
    this.state.set({ kind: 'loaded', request });
    this.flash.set(`Request ${request.status.toLowerCase()}.`);
  }

  protected dismissFlash(): void {
    this.flash.set(null);
  }
}
