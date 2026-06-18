import {
  AfterViewInit,
  Directive,
  ElementRef,
  OnDestroy,
  Renderer2,
  inject,
  input,
} from '@angular/core';

/**
 * Adds a manual drag-to-resize handle to the bottom-right corner of the host element.
 *
 * Designed for native `<dialog>` modals across the app (~33 of them), which all use
 * `overflow: hidden` + `border-radius` on the dialog shell — that combination makes the
 * native CSS `resize: both` unusable (it requires `overflow: auto/scroll` and renders its
 * handle underneath the rounded corner / header gradient). A manual drag-handle avoids both
 * issues and works the same regardless of each dialog's own layout.
 *
 * Usage: add the attribute selector to the `<dialog>` element.
 * ```html
 * <dialog #dialogEl class="email-dialog" appResizableDialog [appResizableDialogMinWidth]="520">
 * ```
 *
 * The directive only handles width/height — it never touches the dialog's position, so it
 * keeps working with browser auto-centering and the existing `dialog-enter` entry animation.
 */
@Directive({
  selector: '[appResizableDialog]',
})
export class ResizableDialogDirective implements AfterViewInit, OnDestroy {
  /** Minimum width in pixels the dialog can be shrunk to. */
  readonly appResizableDialogMinWidth = input(520);
  /** Minimum height in pixels the dialog can be shrunk to. */
  readonly appResizableDialogMinHeight = input(360);
  /** Maximum width as a fraction of the viewport width (0-1). */
  readonly appResizableDialogMaxWidthVw = input(0.95);
  /** Maximum height as a fraction of the viewport height (0-1). */
  readonly appResizableDialogMaxHeightVh = input(0.9);

  private readonly elementRef = inject(ElementRef<HTMLElement>);
  private readonly renderer = inject(Renderer2);

  private handle: HTMLElement | null = null;
  private startX = 0;
  private startY = 0;
  private startWidth = 0;
  private startHeight = 0;
  private resizing = false;

  private readonly onPointerMove = (event: PointerEvent): void => this.handlePointerMove(event);
  private readonly onPointerUp = (event: PointerEvent): void => this.handlePointerUp(event);

  ngAfterViewInit(): void {
    const host = this.elementRef.nativeElement;
    this.renderer.addClass(host, 'is-resizable-dialog');

    this.handle = this.renderer.createElement('div') as HTMLElement;
    this.renderer.addClass(this.handle, 'resizable-dialog__handle');
    this.renderer.setAttribute(this.handle, 'aria-hidden', 'true');
    this.renderer.appendChild(host, this.handle);

    this.handle.addEventListener('pointerdown', (event: PointerEvent) =>
      this.handlePointerDown(event),
    );
  }

  private handlePointerDown(event: PointerEvent): void {
    const host = this.elementRef.nativeElement;
    const rect = host.getBoundingClientRect();

    this.resizing = true;
    this.startX = event.clientX;
    this.startY = event.clientY;
    this.startWidth = rect.width;
    this.startHeight = rect.height;

    // Pin the current size as explicit px values so growing/shrinking is predictable
    // and independent from `max-width`/`width: 100%` set by the component's own stylesheet.
    this.renderer.setStyle(host, 'width', `${this.startWidth}px`);
    this.renderer.setStyle(host, 'height', `${this.startHeight}px`);
    // `is-resized` is permanent (until close) so the component stylesheet can lift its
    // default `max-width`/`max-height` cap once — otherwise that cap would keep clamping the
    // inline px width/height this directive sets, since `max-width` always wins over `width`.
    this.renderer.addClass(host, 'is-resized');
    this.renderer.addClass(host, 'is-resizing');

    this.handle?.setPointerCapture(event.pointerId);
    window.addEventListener('pointermove', this.onPointerMove);
    window.addEventListener('pointerup', this.onPointerUp);

    event.preventDefault();
  }

  private handlePointerMove(event: PointerEvent): void {
    if (!this.resizing) return;

    const host = this.elementRef.nativeElement;
    const deltaX = event.clientX - this.startX;
    const deltaY = event.clientY - this.startY;

    const minWidth = this.appResizableDialogMinWidth();
    const minHeight = this.appResizableDialogMinHeight();
    const maxWidth = window.innerWidth * this.appResizableDialogMaxWidthVw();
    const maxHeight = window.innerHeight * this.appResizableDialogMaxHeightVh();

    const nextWidth = clamp(this.startWidth + deltaX, minWidth, maxWidth);
    const nextHeight = clamp(this.startHeight + deltaY, minHeight, maxHeight);

    this.renderer.setStyle(host, 'width', `${nextWidth}px`);
    this.renderer.setStyle(host, 'height', `${nextHeight}px`);
  }

  private handlePointerUp(event: PointerEvent): void {
    if (!this.resizing) return;
    this.resizing = false;

    this.renderer.removeClass(this.elementRef.nativeElement, 'is-resizing');
    this.handle?.releasePointerCapture(event.pointerId);
    window.removeEventListener('pointermove', this.onPointerMove);
    window.removeEventListener('pointerup', this.onPointerUp);
  }

  ngOnDestroy(): void {
    // Guards against a stale window listener if the dialog is closed mid-drag.
    window.removeEventListener('pointermove', this.onPointerMove);
    window.removeEventListener('pointerup', this.onPointerUp);
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}
