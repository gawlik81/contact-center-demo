import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';

@Component({
  selector: 'app-audio-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './audio-player.component.html',
  styleUrl: './audio-player.component.scss',
})
export class AudioPlayerComponent implements AfterViewInit, OnDestroy {
  readonly src = input.required<string>();
  readonly durationSeconds = input<number | undefined>(undefined);

  private readonly audioRef = viewChild<ElementRef<HTMLAudioElement>>('audioEl');
  private readonly cdr = inject(ChangeDetectorRef);

  readonly isPlaying = signal(false);
  readonly currentTime = signal(0);
  readonly duration = signal(0);
  readonly loadError = signal(false);
  readonly isLoading = signal(true);

  readonly progressPercent = computed(() => {
    const d = this.duration();
    if (d <= 0) return 0;
    return Math.min((this.currentTime() / d) * 100, 100);
  });

  readonly currentTimeFormatted = computed(() => this.formatTime(this.currentTime()));
  readonly durationFormatted = computed(() => {
    const d = this.duration();
    if (d > 0) return this.formatTime(d);
    const hint = this.durationSeconds();
    return hint !== undefined ? this.formatTime(hint) : '--:--';
  });

  private eventListeners: Array<{ event: string; fn: EventListener }> = [];

  ngAfterViewInit(): void {
    const audio = this.audioRef()?.nativeElement;
    if (!audio) return;

    const addListener = (event: string, fn: EventListener): void => {
      audio.addEventListener(event, fn);
      this.eventListeners.push({ event, fn });
    };

    addListener('loadedmetadata', () => {
      this.duration.set(isFinite(audio.duration) ? audio.duration : (this.durationSeconds() ?? 0));
      this.isLoading.set(false);
      this.cdr.markForCheck();
    });

    addListener('timeupdate', () => {
      this.currentTime.set(audio.currentTime);
      this.cdr.markForCheck();
    });

    addListener('ended', () => {
      this.isPlaying.set(false);
      this.currentTime.set(0);
      audio.currentTime = 0;
      this.cdr.markForCheck();
    });

    addListener('error', () => {
      this.loadError.set(true);
      this.isLoading.set(false);
      this.isPlaying.set(false);
      this.cdr.markForCheck();
    });

    addListener('canplay', () => {
      this.isLoading.set(false);
      this.cdr.markForCheck();
    });

    addListener('waiting', () => {
      this.isLoading.set(true);
      this.cdr.markForCheck();
    });

    addListener('playing', () => {
      this.isLoading.set(false);
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    const audio = this.audioRef()?.nativeElement;
    if (audio) {
      this.eventListeners.forEach(({ event, fn }) => audio.removeEventListener(event, fn));
      audio.pause();
    }
  }

  togglePlay(): void {
    const audio = this.audioRef()?.nativeElement;
    if (!audio || this.loadError()) return;

    if (this.isPlaying()) {
      audio.pause();
      this.isPlaying.set(false);
    } else {
      void audio
        .play()
        .then(() => {
          this.isPlaying.set(true);
          this.cdr.markForCheck();
        })
        .catch(() => {
          this.loadError.set(true);
          this.cdr.markForCheck();
        });
    }
  }

  onSeek(event: Event): void {
    const audio = this.audioRef()?.nativeElement;
    if (!audio) return;
    const input = event.target as HTMLInputElement;
    const value = parseFloat(input.value);
    audio.currentTime = value;
    this.currentTime.set(value);
  }

  private formatTime(seconds: number): string {
    const s = Math.floor(seconds);
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
  }
}
