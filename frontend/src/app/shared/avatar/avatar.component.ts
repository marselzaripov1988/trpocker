import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Renders a user's avatar from a stored `avatarUrl` value, which may be:
 *  - an image URL or data-URI (`http(s)://…`, `data:image/…`) → shown as an <img>;
 *  - a short glyph/emoji preset (e.g. "🦊") → shown as text;
 *  - empty/undefined → a neutral fallback (👤, or 🤖 for bots).
 *
 * Keeping it value-driven (not just image-driven) lets the preset gallery store an emoji in the same
 * `avatar_url` column without any backend change. A broken image URL falls back to the default glyph.
 */
@Component({
  selector: 'app-avatar',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="avatar"
      [style.width.px]="size()"
      [style.height.px]="size()"
      [style.fontSize.px]="size() * 0.6"
      data-cy="avatar"
    >
      @if (isImage()) {
        <img
          class="avatar-img"
          [src]="value()"
          [alt]="alt()"
          [width]="size()"
          [height]="size()"
          (error)="onError()"
          referrerpolicy="no-referrer"
        />
      } @else {
        <span class="avatar-glyph">{{ glyph() }}</span>
      }
    </span>
  `,
  styles: [`
    .avatar {
      display: inline-flex; align-items: center; justify-content: center;
      border-radius: 50%; overflow: hidden; line-height: 1;
      background: rgba(255, 255, 255, 0.1); vertical-align: middle; flex: none;
    }
    .avatar-img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .avatar-glyph { user-select: none; }
  `]
})
export class AvatarComponent {
  /** Stored avatar value: an image URL/data-URI, an emoji preset, or null. */
  readonly value = input<string | null | undefined>(null);
  /** Rendered diameter in pixels. */
  readonly size = input(40);
  /** Fallback glyph when there is no image/emoji value (e.g. 🤖 for bots). */
  readonly fallback = input('👤');
  readonly alt = input('');

  /** Set when an <img> fails to load, so we degrade to the fallback glyph. A signal so `isImage` recomputes. */
  private readonly brokenImage = signal(false);

  readonly isImage = computed(() => {
    if (this.brokenImage()) {
      return false;
    }
    const v = (this.value() ?? '').trim();
    return /^(https?:\/\/|data:image\/)/i.test(v);
  });

  readonly glyph = computed(() => {
    const v = (this.value() ?? '').trim();
    // A non-empty, non-URL value is treated as an emoji/text preset; otherwise the fallback.
    return v && !/^(https?:\/\/|data:)/i.test(v) ? v : this.fallback();
  });

  onError(): void {
    this.brokenImage.set(true);
  }
}
