import { Provider, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { LiveEvent, LiveUpdatesService } from '../core/live-updates';

/** Stand-in for the SSE connection: tests push events with `events.next(...)`. */
export class FakeLiveUpdates {
  readonly events = new Subject<LiveEvent>();
  readonly events$ = this.events.asObservable();
  readonly connected = signal(false);
}

export function provideFakeLiveUpdates(fake = new FakeLiveUpdates()): { provider: Provider; fake: FakeLiveUpdates } {
  return { provider: { provide: LiveUpdatesService, useValue: fake }, fake };
}
