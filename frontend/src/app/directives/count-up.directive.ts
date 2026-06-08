import { Directive, ElementRef, Input, NgZone, OnDestroy, inject } from '@angular/core';

/**
 * Animates the element's text from its previous numeric value up/down to the new one (e.g. the pot or a
 * player's stack), so balances tick rather than jump. Writes a locale-formatted integer into the element.
 *
 * Respects `prefers-reduced-motion` and the first render (sets the value instantly). Runs the rAF loop
 * outside Angular so it doesn't trigger change detection on every frame.
 */
@Directive({
  selector: '[appCountUp]',
  standalone: true
})
export class CountUpDirective implements OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>);
  private readonly zone = inject(NgZone);

  private current = 0;
  private initialized = false;
  private rafId: number | null = null;

  /** Animation duration in ms. */
  @Input() countUpDuration = 500;

  @Input('appCountUp')
  set value(next: number | null | undefined) {
    const target = Number(next ?? 0);
    if (!this.initialized || this.prefersReducedMotion()) {
      this.initialized = true;
      this.set(target);
      return;
    }
    if (target === this.current) {
      return;
    }
    this.animate(this.current, target);
  }

  private animate(from: number, to: number): void {
    this.cancel();
    const duration = Math.max(1, this.countUpDuration);
    this.zone.runOutsideAngular(() => {
      const start = performance.now();
      const step = (now: number): void => {
        const t = Math.min(1, (now - start) / duration);
        // easeOutCubic
        const eased = 1 - Math.pow(1 - t, 3);
        this.render(Math.round(from + (to - from) * eased));
        if (t < 1) {
          this.rafId = requestAnimationFrame(step);
        } else {
          this.set(to);
        }
      };
      this.rafId = requestAnimationFrame(step);
    });
  }

  private set(value: number): void {
    this.cancel();
    this.current = value;
    this.render(value);
  }

  private render(value: number): void {
    this.el.nativeElement.textContent = value.toLocaleString('en-US');
  }

  private cancel(): void {
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  private prefersReducedMotion(): boolean {
    return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  ngOnDestroy(): void {
    this.cancel();
  }
}
