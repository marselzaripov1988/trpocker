import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';

interface Particle {
  left: number;     // %
  delay: number;    // s
  duration: number; // s
  rotate: number;   // deg
  color: string;
  size: number;     // px
}

/**
 * A self-contained, non-interactive confetti burst overlay (CSS particles only). Render it with `@if` when a
 * celebration should play (e.g. the local player wins). Particles are precomputed once. Honours
 * prefers-reduced-motion (renders nothing).
 */
@Component({
  selector: 'app-confetti',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="confetti" aria-hidden="true" data-cy="confetti">
      @for (p of particles; track $index) {
        <span
          class="confetti-piece"
          [style.left.%]="p.left"
          [style.width.px]="p.size"
          [style.height.px]="p.size"
          [style.background]="p.color"
          [style.animation-delay.s]="p.delay"
          [style.animation-duration.s]="p.duration"
          [style.--spin]="p.rotate + 'deg'"
        ></span>
      }
    </div>
  `,
  styles: [`
    .confetti {
      position: absolute;
      inset: 0;
      overflow: hidden;
      pointer-events: none;
      z-index: 200;
    }
    .confetti-piece {
      position: absolute;
      top: -16px;
      border-radius: 2px;
      opacity: 0;
      animation-name: confetti-fall;
      animation-timing-function: ease-in;
      animation-iteration-count: 1;
      animation-fill-mode: forwards;
    }
    @keyframes confetti-fall {
      0%   { transform: translateY(-20px) rotate(0deg); opacity: 1; }
      100% { transform: translateY(102vh) rotate(var(--spin, 360deg)); opacity: 0.9; }
    }
    @media (prefers-reduced-motion: reduce) {
      .confetti { display: none; }
    }
  `]
})
export class ConfettiComponent {
  private static readonly COLORS = ['#ffd700', '#ff5e5b', '#38b000', '#6c8cff', '#f0b429', '#ff7ad9'];

  readonly particles: Particle[] = Array.from({ length: 60 }, (_, i) => ({
    left: Math.round(Math.random() * 100),
    delay: +(Math.random() * 0.6).toFixed(2),
    duration: +(2 + Math.random() * 1.8).toFixed(2),
    rotate: 360 + Math.round(Math.random() * 540),
    color: ConfettiComponent.COLORS[i % ConfettiComponent.COLORS.length],
    size: 6 + Math.round(Math.random() * 6)
  }));
}
