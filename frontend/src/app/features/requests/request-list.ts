import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
import { LiveUpdatesService } from '../../core/live-updates';
import { AccessRequest, Page, REQUEST_STATUSES, RequestStatus } from '../../core/models';
import { StatusBadge } from '../../shared/status-badge';

export const PAGE_SIZE = 20;

interface ListQuery {
  status: RequestStatus | null;
  q: string;
  page: number;
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: Page<AccessRequest> }
  | { kind: 'error'; error: ApiError };

/**
 * The filters live in the URL (?status=PENDING&q=git&page=1), so they survive a refresh, can be
 * bookmarked, and the sidebar's "Review queue" is simply a link to ?status=PENDING.
 * Every URL change triggers one API call; switchMap cancels a call that is still in flight.
 */
@Component({
  selector: 'app-request-list',
  imports: [RouterLink, DatePipe, StatusBadge],
  templateUrl: './request-list.html',
  styleUrl: './request-list.css',
})
export class RequestList {
  private readonly api = inject(AccessRequestApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly statuses = REQUEST_STATUSES;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly query = signal<ListQuery>({ status: null, q: '', page: 0 });
  protected readonly state = signal<ListState>({ kind: 'loading' });

  /** Emits when "Try again" is clicked: re-runs the current query without changing the URL. */
  private readonly reload$ = new Subject<void>();

  constructor() {
    const query$ = this.route.queryParamMap.pipe(
      map((params) => ({
        status: (params.get('status') as RequestStatus | null) || null,
        q: params.get('q') ?? '',
        page: Math.max(0, Number(params.get('page')) || 0),
      })),
    );
    combineLatest([query$, this.reload$.pipe(startWith(undefined))])
      .pipe(
        switchMap(([query]) => {
          this.query.set(query);
          return this.api.list({ ...query, size: PAGE_SIZE }).pipe(
            map((page): ListState => ({ kind: 'loaded', page })),
            catchError((err) => of<ListState>({ kind: 'error', error: toApiError(err) })),
            startWith<ListState>({ kind: 'loading' }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));

    // Live updates: any request was submitted or reviewed -> re-run the current query quietly,
    // so new requests and status changes appear without a reload or a spinner.
    inject(LiveUpdatesService)
      .events$.pipe(
        switchMap(() => this.api.list({ ...this.query(), size: PAGE_SIZE }).pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.state.set({ kind: 'loaded', page }));
  }

  protected onStatusChange(event: Event): void {
    this.navigate({ status: (event.target as HTMLSelectElement).value || null, page: null });
  }

  protected onSearch(event: Event, input: HTMLInputElement): void {
    event.preventDefault();
    this.navigate({ q: input.value.trim() || null, page: null });
  }

  protected goToPage(page: number): void {
    this.navigate({ page: page > 0 ? page : null });
  }

  protected retry(): void {
    this.reload$.next();
  }

  protected totalPages(page: Page<AccessRequest>): number {
    return Math.max(1, Math.ceil(page.totalItems / page.size));
  }

  private navigate(changes: Record<string, string | number | null>): void {
    // null removes the parameter from the URL
    this.router.navigate([], { queryParams: changes, queryParamsHandling: 'merge' });
  }
}
