import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { DispositionListEditorComponent } from '../../../../../shared/components/disposition-list-editor/disposition-list-editor.component';

@Component({
  selector: 'app-queue-dispositions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [DispositionListEditorComponent, TranslocoModule],
  templateUrl: './queue-dispositions.component.html',
  styleUrl: './queue-dispositions.component.scss',
})
export class QueueDispositionsComponent {
  readonly queueId = input.required<string>();
}
