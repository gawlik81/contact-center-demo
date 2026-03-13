import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Panel administratora</h1>
    <p>Witaj w module administracyjnym Contact Center.</p>
  `,
})
export class AdminDashboardComponent {}
