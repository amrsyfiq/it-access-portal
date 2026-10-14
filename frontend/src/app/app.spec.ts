import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { FakeLiveUpdates, provideFakeLiveUpdates } from './testing/fake-live-updates';

describe('App shell', () => {
  let live: FakeLiveUpdates;

  beforeEach(() => {
    localStorage.clear();
    const fake = provideFakeLiveUpdates();
    live = fake.fake;
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([]), fake.provider] });
  });

  async function renderApp(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('defaults to the employee and hides the review queue', async () => {
    const el = await renderApp();

    expect((el.querySelector('.user-switch select') as HTMLSelectElement).value).toBe('ted.l');
    expect(el.querySelector('nav')?.textContent).not.toContain('Review queue');
  });

  // Note: the original bug ([value] on <select> applied before the @for options exist) showed up in
  // Edge but not in jsdom, so this test documents the expected behaviour rather than reproducing
  // that bug. It was verified in a real browser; see docs/demo-script.md.
  it('shows the remembered demo user in the dropdown after a reload', async () => {
    localStorage.setItem('itaccess.demoUser', 'alice.admin');
    const el = await renderApp();

    expect((el.querySelector('.user-switch select') as HTMLSelectElement).value).toBe('alice.admin');
    expect(el.querySelector('nav')?.textContent).toContain('Review queue');
  });

  it('switching user updates role-dependent navigation', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const select = fixture.nativeElement.querySelector('.user-switch select') as HTMLSelectElement;

    select.value = 'alice.admin';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('nav')?.textContent).toContain('Review queue');
    expect(localStorage.getItem('itaccess.demoUser')).toBe('alice.admin');
  });

  it('shows the Live badge only while the live-update stream is connected', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.live-badge')).toBeNull();

    live.connected.set(true);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.live-badge')?.textContent).toContain('Live');
  });
});
