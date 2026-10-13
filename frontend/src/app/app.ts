import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DEMO_USERS, DemoUserService } from './core/demo-user';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly demoUser = inject(DemoUserService);
  protected readonly users = DEMO_USERS;

  protected onUserChange(event: Event): void {
    this.demoUser.select((event.target as HTMLSelectElement).value);
  }
}
