import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { DemoUserService } from '../../core/demo-user';
import { RequestStatus } from '../../core/models';
import { aDetail, render, setUpApp } from '../../testing/test-setup';
import { RequestDetail } from './request-detail';

describe('RequestDetail', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;

  beforeEach(async () => {
    ({ harness, http } = await setUpApp());
  });

  afterEach(() => {
    // The notifications panel (covered by notifications-panel.spec.ts) also loads; answer it here.
    http
      .match((r) => r.url.endsWith('/notifications'))
      .filter((r) => !r.cancelled) // superseded by a newer load (switchMap)
      .forEach((r) => r.flush([]));
    http.verify();
  });

  async function open(as: 'ted.l' | 'alice.admin', status: RequestStatus): Promise<HTMLElement> {
    TestBed.inject(DemoUserService).select(as);
    await harness.navigateByUrl('/requests/7', RequestDetail);
    await render(harness);
    http.expectOne('/api/requests/7').flush(aDetail({ id: 7, status }));
    return render(harness);
  }

  const buttons = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('app-review-panel button')).map((b) => b.textContent?.trim());

  it('shows request details and history', async () => {
    const el = await open('ted.l', 'PENDING');

    expect(el.querySelector('h1')?.textContent).toContain('#7');
    expect(el.textContent).toContain('Need to push feature branches');
    expect(el.querySelector('.timeline')?.textContent).toContain('Submitted by ted.l');
  });

  it('shows approve and reject to an admin while the request is pending', async () => {
    const el = await open('alice.admin', 'PENDING');

    expect(buttons(el)).toEqual(['Reject', 'Approve']);
  });

  it('hides review controls from employees', async () => {
    const el = await open('ted.l', 'PENDING');

    expect(el.querySelector('app-review-panel')).toBeNull();
    expect(el.textContent).toContain('Waiting for an administrator');
  });

  it('hides review controls once the request is no longer pending', async () => {
    const el = await open('alice.admin', 'APPROVED');

    expect(el.querySelector('app-review-panel')).toBeNull();
  });

  it('approves with a comment and shows the updated status and history', async () => {
    let el = await open('alice.admin', 'PENDING');
    const comment = el.querySelector('#review-comment') as HTMLTextAreaElement;
    comment.value = 'Approved for Q4';
    comment.dispatchEvent(new Event('input'));
    (Array.from(el.querySelectorAll('app-review-panel button')).find((b) => b.textContent?.includes('Approve')) as HTMLButtonElement).click();
    await render(harness);

    const req = http.expectOne('/api/requests/7/status');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'APPROVED', comment: 'Approved for Q4' });
    expect(req.request.headers.get('X-Demo-Role')).toBe('ADMIN');

    const approved = aDetail({ id: 7, status: 'APPROVED' });
    approved.history.push({
      id: 2,
      oldStatus: 'PENDING',
      newStatus: 'APPROVED',
      comment: 'Approved for Q4',
      changedAt: '2026-10-13T06:00:00Z',
      changedBy: 'alice.admin',
    });
    req.flush(approved);
    el = await render(harness);

    expect(el.querySelector('app-review-panel')).toBeNull();
    expect(el.querySelector('.summary app-status-badge')?.textContent).toContain('APPROVED');
    expect(el.querySelector('[role=status]')?.textContent).toContain('Request approved.');
    expect(el.querySelector('.timeline')?.textContent).toContain('Approved for Q4');
  });

  it('lets the user dismiss the success message', async () => {
    let el = await open('alice.admin', 'PENDING');
    (Array.from(el.querySelectorAll('app-review-panel button')).find((b) => b.textContent?.includes('Approve')) as HTMLButtonElement).click();
    await render(harness);
    http.expectOne('/api/requests/7/status').flush(aDetail({ id: 7, status: 'APPROVED' }));
    el = await render(harness);
    expect(el.querySelector('[role=status]')?.textContent).toContain('Request approved.');

    (el.querySelector('[role=status] button[aria-label="Dismiss message"]') as HTMLButtonElement).click();
    el = await render(harness);

    expect(el.querySelector('[role=status]')).toBeNull();
    // only the message goes away; the request itself is still shown
    expect(el.querySelector('.summary app-status-badge')?.textContent).toContain('APPROVED');
  });

  it('shows the conflict message and reloads when someone else already reviewed it', async () => {
    let el = await open('alice.admin', 'PENDING');
    (Array.from(el.querySelectorAll('app-review-panel button')).find((b) => b.textContent?.includes('Reject')) as HTMLButtonElement).click();
    await render(harness);

    http.expectOne('/api/requests/7/status').flush(
      { title: 'Invalid status transition', status: 409, detail: 'Access request 7 is APPROVED and can no longer be changed' },
      { status: 409, statusText: 'Conflict' },
    );
    await render(harness);

    // the page reloads the request to show its real, current status
    http.expectOne('/api/requests/7').flush(aDetail({ id: 7, status: 'APPROVED' }));
    el = await render(harness);
    expect(el.querySelector('.summary app-status-badge')?.textContent).toContain('APPROVED');
    expect(el.querySelector('app-review-panel')).toBeNull();
  });

  it('shows not found without a retry button', async () => {
    await harness.navigateByUrl('/requests/99', RequestDetail);
    await render(harness);
    http.expectOne('/api/requests/99').flush(
      { title: 'Not found', status: 404, detail: 'Access request 99 was not found' },
      { status: 404, statusText: 'Not Found' },
    );
    const el = await render(harness);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('Access request 99 was not found');
    expect(el.querySelector('[role=alert] button')).toBeNull();
  });
});
