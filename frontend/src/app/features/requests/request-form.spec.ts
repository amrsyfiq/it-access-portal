import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { aRequest, render, setUpApp } from '../../testing/test-setup';
import { RequestForm } from './request-form';

describe('RequestForm', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    ({ harness, http } = await setUpApp());
    await harness.navigateByUrl('/requests/new?system=GitLab', RequestForm);
    el = await render(harness);
  });

  afterEach(() => http.verify());

  function setValue(selector: string, value: string): void {
    const field = el.querySelector(selector) as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
    field.dispatchEvent(new Event('blur'));
  }

  async function submit(): Promise<void> {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    el = await render(harness);
  }

  it('prefills the system from the catalog link and the requester from the demo user', () => {
    expect((el.querySelector('#systemName') as HTMLInputElement).value).toBe('GitLab');
    expect((el.querySelector('#requesterName') as HTMLInputElement).value).toBe('Ted L');
  });

  it('shows required-field errors and does not call the API when the form is invalid', async () => {
    setValue('#systemName', '');
    await submit();

    const errors = Array.from(el.querySelectorAll('.error-text')).map((e) => e.textContent?.trim());
    expect(errors).toContain('This field is required.');
    expect(el.querySelectorAll('.field.invalid').length).toBe(3); // system, access level, reason
    http.expectNone('/api/requests');
  });

  it('enforces the minimum reason length', async () => {
    setValue('#reason', 'too short');
    el = await render(harness);

    expect(el.textContent).toContain('Must be at least 10 characters.');
  });

  it('posts the request with demo-user headers and navigates to the new request', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    setValue('#accessLevel', 'WRITE');
    setValue('#reason', 'Need to push feature branches');
    await submit();

    const req = http.expectOne('/api/requests');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      requesterName: 'Ted L',
      systemName: 'GitLab',
      accessLevel: 'WRITE',
      reason: 'Need to push feature branches',
    });
    expect(req.request.headers.get('X-Demo-User')).toBe('ted.l');
    expect(req.request.headers.get('X-Demo-Role')).toBe('EMPLOYEE');
    req.flush(aRequest({ id: 42 }), { status: 201, statusText: 'Created' });

    expect(navigate).toHaveBeenCalledWith(['/requests', 42], { state: { flash: 'Request submitted.' } });
  });

  it('shows backend validation errors next to the fields', async () => {
    setValue('#accessLevel', 'READ');
    setValue('#reason', 'A valid looking reason');
    await submit();

    http.expectOne('/api/requests').flush(
      {
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid',
        errors: { reason: 'reason rejected by server' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    el = await render(harness);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('One or more fields are invalid');
    expect(el.textContent).toContain('reason rejected by server');
    expect((el.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(false);
  });
});
