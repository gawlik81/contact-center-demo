import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

/** Empty state of the contact workspace when the agent has no open contact (extracted in FE-124). */
@Component({
  selector: 'app-no-contacts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './no-contacts.component.html',
  styleUrl: './no-contacts.component.scss',
})
export class NoContactsComponent {}
