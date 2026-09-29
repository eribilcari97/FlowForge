import { Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { interval, map } from 'rxjs';

export function clock(periodMs = 1000): Signal<number> {
  return toSignal(interval(periodMs).pipe(map(() => Date.now())), { initialValue: Date.now() });
}
