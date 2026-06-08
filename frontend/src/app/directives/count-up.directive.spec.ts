import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CountUpDirective } from './count-up.directive';

@Component({
  standalone: true,
  imports: [CountUpDirective],
  template: `<span [appCountUp]="value"></span>`
})
class HostComponent {
  value = 0;
}

describe('CountUpDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let span: HTMLElement;

  beforeEach(() => {
    // Force the reduced-motion path so values are set synchronously (deterministic, no rAF in the test).
    window.matchMedia = ((q: string) => ({
      matches: true, media: q, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined,
      dispatchEvent: () => false
    })) as unknown as typeof window.matchMedia;

    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    span = (fixture.nativeElement as HTMLElement).querySelector('span')!;
  });

  it('renders the initial value formatted', () => {
    fixture.componentInstance.value = 1500;
    fixture.detectChanges();
    expect(span.textContent).toBe('1,500');
  });

  it('updates to the new value when it changes', () => {
    fixture.detectChanges(); // 0
    fixture.componentInstance.value = 2750;
    fixture.detectChanges();
    expect(span.textContent).toBe('2,750');
  });

  it('handles null/undefined as 0', () => {
    fixture.componentInstance.value = null as unknown as number;
    fixture.detectChanges();
    expect(span.textContent).toBe('0');
  });
});
