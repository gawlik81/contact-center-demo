import { Component, ChangeDetectionStrategy } from '@angular/core';
import { AppShellComponent } from '../../shared/components/app-shell/app-shell.component';

@Component({
  selector: 'app-supervisor-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppShellComponent],
  template: `<cc-app-shell />`,
})
export class SupervisorShellComponent {}
