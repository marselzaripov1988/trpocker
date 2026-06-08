import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AvatarComponent } from './avatar.component';

describe('AvatarComponent', () => {
  let fixture: ComponentFixture<AvatarComponent>;
  let component: AvatarComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AvatarComponent] }).compileComponents();
    fixture = TestBed.createComponent(AvatarComponent);
    component = fixture.componentInstance;
  });

  function set(value: string | null): void {
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
  }

  it('renders an <img> for an http(s) URL', () => {
    set('https://cdn.example/a.png');
    expect(component.isImage()).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('img.avatar-img')).toBeTruthy();
  });

  it('renders an <img> for a data-URI image', () => {
    set('data:image/png;base64,abc');
    expect(component.isImage()).toBe(true);
  });

  it('renders an emoji preset as a glyph (not an image)', () => {
    set('🦊');
    expect(component.isImage()).toBe(false);
    expect(component.glyph()).toBe('🦊');
    expect((fixture.nativeElement as HTMLElement).querySelector('.avatar-glyph')?.textContent?.trim()).toBe('🦊');
  });

  it('falls back to 👤 when empty', () => {
    set(null);
    expect(component.isImage()).toBe(false);
    expect(component.glyph()).toBe('👤');
  });

  it('honours a custom fallback (e.g. bot)', () => {
    fixture.componentRef.setInput('fallback', '🤖');
    set('');
    expect(component.glyph()).toBe('🤖');
  });

  it('degrades to the fallback glyph when the image fails to load', () => {
    set('https://broken.example/x.png');
    expect(component.isImage()).toBe(true);
    component.onError();
    fixture.detectChanges();
    expect(component.isImage()).toBe(false);
    expect(component.glyph()).toBe('👤');
  });
});
