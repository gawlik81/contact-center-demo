import { Injectable, signal } from '@angular/core';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: number;
  type: ToastType;
  message: string;
  /** Auto-dismiss delay in ms */
  duration: number;
}

let nextId = 0;

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly _toasts = signal<Toast[]>([]);

  /** Read-only list of active toasts consumed by ToastContainerComponent */
  readonly toasts = this._toasts.asReadonly();

  success(message: string): void {
    this.add({ type: 'success', message, duration: 4000 });
  }

  error(message: string): void {
    this.add({ type: 'error', message, duration: 6000 });
  }

  warning(message: string): void {
    this.add({ type: 'warning', message, duration: 6000 });
  }

  info(message: string): void {
    this.add({ type: 'info', message, duration: 4000 });
  }

  dismiss(id: number): void {
    this._toasts.update((list) => list.filter((t) => t.id !== id));
  }

  private add(opts: Omit<Toast, 'id'>): void {
    const toast: Toast = { id: nextId++, ...opts };
    this._toasts.update((list) => [...list, toast]);
    setTimeout(() => this.dismiss(toast.id), toast.duration);
  }
}
