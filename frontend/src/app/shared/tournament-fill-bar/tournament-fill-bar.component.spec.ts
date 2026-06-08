import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TournamentFillBarComponent } from './tournament-fill-bar.component';

describe('TournamentFillBarComponent', () => {
  let fixture: ComponentFixture<TournamentFillBarComponent>;
  let component: TournamentFillBarComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TournamentFillBarComponent]
    }).compileComponents();
    fixture = TestBed.createComponent(TournamentFillBarComponent);
    component = fixture.componentInstance;
  });

  function setInputs(registered: number, max: number): void {
    fixture.componentRef.setInput('registered', registered);
    fixture.componentRef.setInput('max', max);
    fixture.detectChanges();
  }

  it('computes a rounded percentage', () => {
    setInputs(3, 6);
    expect(component.percent()).toBe(50);
    setInputs(1, 3);
    expect(component.percent()).toBe(33);
  });

  it('clamps to 100 when over-filled and reports full', () => {
    setInputs(9, 6);
    expect(component.percent()).toBe(100);
    expect(component.level()).toBe('full');
  });

  it('returns 0 when max is non-positive (avoids NaN)', () => {
    setInputs(5, 0);
    expect(component.percent()).toBe(0);
    expect(component.level()).toBe('low');
  });

  it('maps percentage to level thresholds', () => {
    setInputs(1, 10); // 10%
    expect(component.level()).toBe('low');
    setInputs(4, 10); // 40%
    expect(component.level()).toBe('medium');
    setInputs(7, 10); // 70%
    expect(component.level()).toBe('high');
  });

  it('renders the registered/max and percent label', () => {
    setInputs(2, 8);
    const text = (fixture.nativeElement as HTMLElement).querySelector('.fill-label')?.textContent ?? '';
    expect(text).toContain('2/8');
    expect(text).toContain('25%');
  });
});
