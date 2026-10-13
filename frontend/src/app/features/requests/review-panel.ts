import { Component, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
import { AccessRequestDetail, ReviewDecision } from '../../core/models';

/**
 * Approve / reject controls. The parent decides *whether* to show it (admin + PENDING);
 * this component only performs the review and reports the result.
 */
@Component({
  selector: 'app-review-panel',
  imports: [ReactiveFormsModule],
  template: `
    <section class="card review" aria-labelledby="review-heading">
      <h2 id="review-heading">Review this request</h2>
      @if (error(); as err) {
        <div class="alert alert-error" role="alert">{{ err.message }}</div>
      }
      <div class="field" [class.invalid]="comment.invalid">
        <label for="review-comment">Comment <span class="muted">(optional)</span></label>
        <textarea id="review-comment" rows="3" [formControl]="comment"></textarea>
        @if (comment.hasError('maxlength')) {
          <p class="error-text">Must be at most 1000 characters.</p>
        }
      </div>
      <div class="actions">
        <button class="btn btn-danger" type="button" [disabled]="busy() !== null || comment.invalid"
                (click)="review('REJECTED')">
          {{ busy() === 'REJECTED' ? 'Rejecting…' : 'Reject' }}
        </button>
        <button class="btn btn-success" type="button" [disabled]="busy() !== null || comment.invalid"
                (click)="review('APPROVED')">
          {{ busy() === 'APPROVED' ? 'Approving…' : 'Approve' }}
        </button>
      </div>
    </section>
  `,
  styles: `
    .review { margin-top: 16px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; }
  `,
})
export class ReviewPanel {
  private readonly api = inject(AccessRequestApi);

  readonly requestId = input.required<number>();
  /** Emitted with the updated request after a successful review. */
  readonly reviewed = output<AccessRequestDetail>();
  /** Emitted on 409 Conflict: someone else already reviewed it, so the parent should reload. */
  readonly conflict = output<void>();

  protected readonly comment = inject(NonNullableFormBuilder).control('', Validators.maxLength(1000));
  protected readonly busy = signal<ReviewDecision | null>(null);
  protected readonly error = signal<ApiError | null>(null);

  protected review(decision: ReviewDecision): void {
    this.busy.set(decision);
    this.error.set(null);
    this.api.review(this.requestId(), { status: decision, comment: this.comment.value.trim() || null }).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.reviewed.emit(updated);
      },
      error: (err) => {
        this.busy.set(null);
        this.error.set(toApiError(err));
        if (err?.status === 409) this.conflict.emit();
      },
    });
  }
}
