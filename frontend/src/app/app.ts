import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth';
import { DEMO_USERS, DemoUserService } from './core/demo-user';
import { LiveUpdatesService } from './core/live-updates';
import { SessionService } from './core/session';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly session = inject(SessionService);
  protected readonly demoUser = inject(DemoUserService);
  protected readonly auth = inject(AuthService);
  protected readonly live = inject(LiveUpdatesService);
  protected readonly users = DEMO_USERS;

  protected onUserChange(event: Event): void {
    this.demoUser.select((event.target as HTMLSelectElement).value);
  }

  protected signOut(): void {
    this.auth.logout();
  }
}
