import { Component, ChangeDetectionStrategy } from '@angular/core';
import { AppShellComponent } from '../../shared/components/app-shell/app-shell.component';

@Component({
  selector: 'app-agent-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppShellComponent],
  template: `<cc-app-shell />`,
})
export class AgentShellComponent {}
