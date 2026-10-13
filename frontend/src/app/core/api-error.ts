import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/** What components need to show an error: a message plus any per-field validation errors. */
export interface ApiError {
  status: number;
  message: string;
  fieldErrors: Record<string, string>;
}

export function toApiError(err: unknown): ApiError {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return {
        status: 0,
        message: 'Cannot reach the server. Check that the backend is running.',
        fieldErrors: {},
      };
    }
    const body = (typeof err.error === 'object' ? err.error : null) as ProblemDetail | null;
    return {
      status: err.status,
      message: body?.detail ?? body?.title ?? `Request failed (HTTP ${err.status})`,
      fieldErrors: body?.errors ?? {},
    };
  }
  return { status: -1, message: 'An unexpected error occurred.', fieldErrors: {} };
}
