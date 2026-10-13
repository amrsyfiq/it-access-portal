// TypeScript mirrors of the backend DTOs (com.itaccess.portal.dto.*).
// Dates arrive as ISO-8601 strings, e.g. "2026-10-13T05:52:58.249774Z".

export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type AccessLevel = 'READ' | 'WRITE' | 'ADMIN';
export type ReviewDecision = 'APPROVED' | 'REJECTED';

export const REQUEST_STATUSES: readonly RequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];
export const ACCESS_LEVELS: readonly AccessLevel[] = ['READ', 'WRITE', 'ADMIN'];

/** AccessRequestResponse */
export interface AccessRequest {
  id: number;
  requesterName: string;
  systemName: string;
  accessLevel: AccessLevel;
  reason: string;
  status: RequestStatus;
  createdAt: string;
  updatedAt: string;
}

/** HistoryEntryResponse */
export interface HistoryEntry {
  id: number;
  oldStatus: RequestStatus | null;
  newStatus: RequestStatus;
  comment: string | null;
  changedAt: string;
  changedBy: string;
}

/** AccessRequestDetailResponse */
export interface AccessRequestDetail extends AccessRequest {
  history: HistoryEntry[];
}

/** PageResponse<T> */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
}

/** CreateAccessRequest */
export interface CreateAccessRequestPayload {
  requesterName: string;
  systemName: string;
  accessLevel: AccessLevel;
  reason: string;
}

/** ReviewRequest */
export interface ReviewPayload {
  status: ReviewDecision;
  comment?: string | null;
}

export interface RequestListQuery {
  status?: RequestStatus | null;
  q?: string | null;
  page?: number;
  size?: number;
}

/** RFC 9457 problem detail returned by GlobalExceptionHandler. */
export interface ProblemDetail {
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
}
