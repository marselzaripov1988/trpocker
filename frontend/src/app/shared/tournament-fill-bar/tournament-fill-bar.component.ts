import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Tournament registration fill indicator: a coloured progress bar with a `registered/max · NN%` label.
 * Reused by the player tournament card and the admin tournament list/detail. The percentage is clamped to
 * 0–100 and is 0 when `max` is missing/non-positive (so an unbounded/unknown cap renders empty, not NaN).
 */
@Component({
  selector: 'app-tournament-fill-bar',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="fill-bar"
      role="progressbar"
      [attr.aria-valuenow]="percent()"
      aria-valuemin="0"
      aria-valuemax="100"
      [attr.aria-label]="'Registration ' + percent() + '% full (' + registered() + ' of ' + max() + ')'"
      data-cy="tournament-fill-bar"
    >
      <div class="fill-track">
        <div class="fill-progress" [class]="level()" [style.width.%]="percent()"></div>
      </div>
      <span class="fill-label" [class.compact]="compact()">
        {{ registered() }}/{{ max() }} · <strong>{{ percent() }}%</strong>
      </span>
    </div>
  `,
  styles: [`
    .fill-bar { display: flex; align-items: center; gap: 8px; width: 100%; }
    .fill-track {
      position: relative; flex: 1 1 auto; height: 8px; min-width: 60px;
      background: rgba(255, 255, 255, 0.12); border-radius: 999px; overflow: hidden;
    }
    .fill-progress {
      height: 100%; border-radius: 999px; transition: width 0.3s ease, background-color 0.3s ease;
    }
    .fill-progress.low { background: #6c8cff; }
    .fill-progress.medium { background: #f0b429; }
    .fill-progress.high { background: #38b000; }
    .fill-progress.full { background: #2ecc71; }
    .fill-label { font-size: 0.8rem; white-space: nowrap; opacity: 0.9; }
    .fill-label.compact { font-size: 0.72rem; }
    .fill-label strong { font-weight: 700; }
  `]
})
export class TournamentFillBarComponent {
  readonly registered = input.required<number>();
  readonly max = input.required<number>();
  /** Slightly smaller label for dense table cells. */
  readonly compact = input(false);

  readonly percent = computed(() => {
    const max = this.max();
    if (!max || max <= 0) {
      return 0;
    }
    const registered = this.registered() ?? 0;
    return Math.min(100, Math.max(0, Math.round((registered / max) * 100)));
  });

  readonly level = computed<'low' | 'medium' | 'high' | 'full'>(() => {
    const p = this.percent();
    if (p >= 100) return 'full';
    if (p >= 70) return 'high';
    if (p >= 40) return 'medium';
    return 'low';
  });
}
