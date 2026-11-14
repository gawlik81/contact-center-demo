import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

/** WebSocket connection-loss banner of the agent desktop (extracted in FE-124). */
@Component({
  selector: 'app-ws-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './ws-banner.component.html',
  styleUrl: './ws-banner.component.scss',
})
export class WsBannerComponent {
  /** true: the client is retrying; false: the connection is down. */
  readonly reconnecting = input(false);
  /** Terminal rejection (FE-169): automatic reconnect is off, the agent has to act. */
  readonly rejection = input<'FORBIDDEN' | 'SESSION_EXPIRED' | null>(null);
  /** The retry action is running (profile refresh in flight): button disabled, aria-busy. */
  readonly busy = input(false);
  /** FORBIDDEN action: refresh the profile and open a new connection. */
  readonly retry = output<void>();
  /** SESSION_EXPIRED action: go to the login page. */
  readonly signIn = output<void>();
}
