import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-supervisor-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Panel supervisora</h1>
    <p>Witaj w module supervisora Contact Center.</p>
  `,
})
export class SupervisorDashboardComponent {}
