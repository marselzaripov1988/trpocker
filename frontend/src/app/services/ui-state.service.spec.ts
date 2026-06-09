import { TestBed } from '@angular/core/testing';
import { UiStateService } from './ui-state.service';

/**
 * UiStateService is pure signal-backed UI state (modals, sound/animation/theme preferences) persisted to
 * localStorage. These tests exercise every mutator + derived signal so the settings surface stays covered.
 */
describe('UiStateService', () => {
  let service: UiStateService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(UiStateService);
  });

  it('starts from sane defaults', () => {
    expect(service.soundEnabled()).toBe(true);
    expect(service.animationsEnabled()).toBe(true);
    expect(service.theme()).toBe('green');
    expect(service.isAnyModalOpen()).toBe(false);
    expect(service.themeClass()).toBe('theme-green');
  });

  it('opens/closes/toggles each modal and reports isAnyModalOpen', () => {
    service.openRaiseModal();
    expect(service.showRaiseModal()).toBe(true);
    expect(service.isAnyModalOpen()).toBe(true);
    service.closeRaiseModal();
    expect(service.showRaiseModal()).toBe(false);

    service.toggleRaiseModal();
    expect(service.showRaiseModal()).toBe(true);

    service.openSettingsModal();
    service.openResultModal();
    service.openHelpModal();
    expect(service.showSettingsModal()).toBe(true);
    expect(service.showResultModal()).toBe(true);
    expect(service.showHelpModal()).toBe(true);

    service.closeSettingsModal();
    service.closeResultModal();
    service.closeHelpModal();
    service.closeAllModals();
    expect(service.isAnyModalOpen()).toBe(false);
  });

  it('toggles and sets the preference flags', () => {
    service.toggleSound();
    expect(service.soundEnabled()).toBe(false);
    service.setSoundEnabled(true);
    expect(service.soundEnabled()).toBe(true);

    service.toggleAnimations();
    expect(service.animationsEnabled()).toBe(false);
    service.setAnimationsEnabled(true);
    expect(service.animationsEnabled()).toBe(true);

    service.toggleCardAnimations();
    service.toggleChipAnimations();
    service.toggleAutoMuck();
    service.togglePotOdds();
    service.toggleCompactMode();
    service.toggleBetHistory();
    expect(service.autoMuck()).toBe(false);
    expect(service.showPotOdds()).toBe(true);
    expect(service.compactMode()).toBe(true);
    expect(service.showBetHistory()).toBe(true);
    // showCardAnimations/showChipAnimations are gated by animationsEnabled
    expect(service.showCardAnimations()).toBe(false);
    expect(service.showChipAnimations()).toBe(false);
  });

  it('derives animationDuration from speed and the master animations flag', () => {
    service.setAnimationsEnabled(true);
    service.setAnimationSpeed('fast');
    expect(service.animationDuration()).toBe(150);
    service.setAnimationSpeed('slow');
    expect(service.animationDuration()).toBe(600);
    service.setAnimationsEnabled(false);
    expect(service.animationDuration()).toBe(0);
    expect(service.animationSpeed()).toBe('slow');
  });

  it('sets theme + card back and reflects themeClass', () => {
    service.setTheme('blue');
    expect(service.theme()).toBe('blue');
    expect(service.themeClass()).toBe('theme-blue');
    expect(document.body.classList.contains('theme-blue')).toBe(true);

    service.setCardBackDesign('classic');
    expect(service.cardBackDesign()).toBe('classic');
  });

  it('persists settings to localStorage (modals are never persisted open)', () => {
    service.setTheme('red');
    service.openRaiseModal();
    TestBed.flushEffects(); // the save effect runs on flush, not synchronously
    const saved = JSON.parse(localStorage.getItem('truholdem_ui_settings') ?? '{}');
    expect(saved.theme).toBe('red');
    expect(saved.showRaiseModal).toBe(false);
  });

  it('reloads persisted settings into a fresh instance', () => {
    service.setTheme('light');
    service.setSoundEnabled(false);
    TestBed.flushEffects(); // ensure the save effect wrote to localStorage before re-injecting

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(UiStateService);
    expect(reloaded.theme()).toBe('light');
    expect(reloaded.soundEnabled()).toBe(false);
  });

  it('resetToDefaults restores the baseline', () => {
    service.setTheme('dark');
    service.setSoundEnabled(false);
    service.resetToDefaults();
    expect(service.theme()).toBe('green');
    expect(service.soundEnabled()).toBe(true);
  });
});
