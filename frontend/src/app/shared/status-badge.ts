import { Component, input } from '@angular/core';
import { RequestStatus } from '../core/models';

@Component({
  selector: 'app-status-badge',
  template: `<span class="badge" [class]="'badge badge-' + status().toLowerCase()">{{ status() }}</span>`,
  styles: `
    .badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 700;
      letter-spacing: 0.04em;
    }
    .badge-pending { background: var(--warning-soft); color: var(--warning); }
    .badge-approved { background: var(--success-soft); color: var(--success); }
    .badge-rejected { background: var(--danger-soft); color: var(--danger); }
  `,
})
export class StatusBadge {
  readonly status = input.required<RequestStatus>();
}
