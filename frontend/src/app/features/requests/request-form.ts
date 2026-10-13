import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AccessRequestApi } from '../../core/access-request-api';
import { ApiError, toApiError } from '../../core/api-error';
import { DemoUserService } from '../../core/demo-user';
import { ACCESS_LEVELS, AccessLevel } from '../../core/models';

type FieldName = 'requesterName' | 'systemName' | 'accessLevel' | 'reason';

@Component({
  selector: 'app-request-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './request-form.html',
  styleUrl: './request-form.css',
})
export class RequestForm {
  private readonly api = inject(AccessRequestApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly accessLevels = ACCESS_LEVELS;
  protected readonly submitting = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  // Same limits as the backend's Bean Validation annotations on CreateAccessRequest.
  protected readonly form = this.fb.group({
    requesterName: [inject(DemoUserService).current().displayName, [Validators.required, Validators.maxLength(100)]],
    systemName: [
      this.route.snapshot.queryParamMap.get('system') ?? '',
      [Validators.required, Validators.maxLength(100)],
    ],
    accessLevel: ['' as AccessLevel | '', Validators.required],
    reason: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(1000)]],
  });

  /** Client-side message for a field once the user has touched it, else the server's message. */
  protected fieldError(name: FieldName): string | null {
    const control = this.form.controls[name];
    if (control.invalid && (control.touched || control.dirty)) {
      if (control.hasError('required')) return 'This field is required.';
      if (control.hasError('minlength')) {
        return `Must be at least ${control.getError('minlength').requiredLength} characters.`;
      }
      if (control.hasError('maxlength')) {
        return `Must be at most ${control.getError('maxlength').requiredLength} characters.`;
      }
    }
    return this.error()?.fieldErrors[name] ?? null;
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);

    const value = this.form.getRawValue();
    this.api
      .create({ ...value, accessLevel: value.accessLevel as AccessLevel })
      .subscribe({
        next: (created) => {
          this.router.navigate(['/requests', created.id], { state: { flash: 'Request submitted.' } });
        },
        error: (err) => {
          this.error.set(toApiError(err));
          this.submitting.set(false);
        },
      });
  }
}
