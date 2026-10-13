import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AccessRequest,
  AccessRequestDetail,
  CreateAccessRequestPayload,
  HistoryEntry,
  Page,
  RequestListQuery,
  ReviewPayload,
} from './models';

/**
 * The only place that knows the REST URLs. Components call these methods and never use
 * HttpClient directly, so switching to the WSO2 gateway is a config change, not a code change.
 */
@Injectable({ providedIn: 'root' })
export class AccessRequestApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/requests`;

  list(query: RequestListQuery = {}): Observable<Page<AccessRequest>> {
    let params = new HttpParams();
    if (query.status) params = params.set('status', query.status);
    if (query.q?.trim()) params = params.set('q', query.q.trim());
    if (query.page != null) params = params.set('page', query.page);
    if (query.size != null) params = params.set('size', query.size);
    return this.http.get<Page<AccessRequest>>(this.baseUrl, { params });
  }

  get(id: number): Observable<AccessRequestDetail> {
    return this.http.get<AccessRequestDetail>(`${this.baseUrl}/${id}`);
  }

  history(id: number): Observable<HistoryEntry[]> {
    return this.http.get<HistoryEntry[]>(`${this.baseUrl}/${id}/history`);
  }

  create(payload: CreateAccessRequestPayload): Observable<AccessRequest> {
    return this.http.post<AccessRequest>(this.baseUrl, payload);
  }

  review(id: number, payload: ReviewPayload): Observable<AccessRequestDetail> {
    return this.http.patch<AccessRequestDetail>(`${this.baseUrl}/${id}/status`, payload);
  }
}
