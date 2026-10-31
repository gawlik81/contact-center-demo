import { ChangeDetectionStrategy, Component, input } from '@angular/core';
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
}
