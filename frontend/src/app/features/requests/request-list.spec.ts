import { HttpTestingController } from '@angular/common/http/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { FakeLiveUpdates } from '../../testing/fake-live-updates';
import { aRequest, render, setUpApp } from '../../testing/test-setup';
import { RequestList } from './request-list';

describe('RequestList', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let live: FakeLiveUpdates;

  beforeEach(async () => {
    ({ harness, http, live } = await setUpApp());
  });

  afterEach(() => http.verify());

  const listCall = (url: string) => url === '/api/requests';

  it('shows a loading state, then one row per request', async () => {
    await harness.navigateByUrl('/requests', RequestList);
    let el = await render(harness);
    expect(el.textContent).toContain('Loading requests');

    http.expectOne((r) => listCall(r.url)).flush({
      items: [aRequest({ id: 1, systemName: 'GitLab' }), aRequest({ id: 2, systemName: 'Jira', status: 'APPROVED' })],
      page: 0,
      size: 20,
      totalItems: 2,
    });
    el = await render(harness);

    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('#1');
    expect(rows[0].textContent).toContain('GitLab');
    expect(rows[0].textContent).toContain('PENDING');
    expect(rows[1].textContent).toContain('APPROVED');
    expect(el.textContent).not.toContain('Loading requests');
  });

  it('sends the status filter from the URL to the API', async () => {
    await harness.navigateByUrl('/requests?status=PENDING&q=git', RequestList);
    await render(harness);

    const req = http.expectOne((r) => listCall(r.url));
    expect(req.request.params.get('status')).toBe('PENDING');
    expect(req.request.params.get('q')).toBe('git');
    req.flush({ items: [], page: 0, size: 20, totalItems: 0 });
    const el = await render(harness);
    expect(el.querySelector('h1')?.textContent).toContain('Review queue');
    // the dropdown must reflect the filter from the URL (see the note in app.spec.ts about jsdom)
    expect((el.querySelector('.filters select') as HTMLSelectElement).value).toBe('PENDING');
    expect((el.querySelector('#list-search') as HTMLInputElement).value).toBe('git');
  });

  it('shows an empty state when there are no requests', async () => {
    await harness.navigateByUrl('/requests', RequestList);
    await render(harness);
    http.expectOne((r) => listCall(r.url)).flush({ items: [], page: 0, size: 20, totalItems: 0 });
    const el = await render(harness);

    expect(el.querySelector('table')).toBeNull();
    expect(el.textContent).toContain('No requests found.');
  });

  it('shows the API error message and can retry', async () => {
    await harness.navigateByUrl('/requests', RequestList);
    await render(harness);
    http.expectOne((r) => listCall(r.url)).flush(
      { title: 'Internal error', status: 500, detail: 'An unexpected error occurred' },
      { status: 500, statusText: 'Server Error' },
    );
    let el = await render(harness);
    expect(el.querySelector('[role=alert]')?.textContent).toContain('An unexpected error occurred');

    (el.querySelector('[role=alert] button') as HTMLButtonElement).click();
    await render(harness);
    http.expectOne((r) => listCall(r.url)).flush({ items: [aRequest()], page: 0, size: 20, totalItems: 1 });
    el = await render(harness);
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
  });

  it('explains when the backend cannot be reached', async () => {
    await harness.navigateByUrl('/requests', RequestList);
    await render(harness);
    http.expectOne((r) => listCall(r.url)).error(new ProgressEvent('error'), { status: 0 });
    const el = await render(harness);

    expect(el.textContent).toContain('Cannot reach the server');
  });

  it('re-runs the current query quietly when any request changes (live update)', async () => {
    await harness.navigateByUrl('/requests?status=PENDING', RequestList);
    await render(harness);
    http.expectOne((r) => listCall(r.url)).flush({ items: [aRequest({ id: 1 })], page: 0, size: 20, totalItems: 1 });
    await render(harness);

    live.events.next({
      type: 'request',
      data: { requestId: 2, eventType: 'AccessRequestSubmitted', status: 'PENDING', actor: 'ted.l', occurredAt: '' },
    });
    const req = http.expectOne((r) => listCall(r.url));
    expect(req.request.params.get('status')).toBe('PENDING'); // same filter as on screen
    req.flush({ items: [aRequest({ id: 2 }), aRequest({ id: 1 })], page: 0, size: 20, totalItems: 2 });
    const el = await render(harness);

    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(el.textContent).not.toContain('Loading requests');
  });
});
