import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-agent-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Agent Desktop</h1>
    <p>Witaj w module agenta Contact Center.</p>
  `,
})
export class AgentDashboardComponent {}
