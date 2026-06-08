import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { AdminCashComponent } from './admin-cash.component';
import { AdminCashService } from '../services/admin-cash.service';
import { ErrorHandlerService } from '../../services/error-handler.service';
import { CashTable } from '../../cash/cash.models';

describe('AdminCashComponent', () => {
  let fixture: ComponentFixture<AdminCashComponent>;
  let component: AdminCashComponent;
  let serviceMock: jasmine.SpyObj<AdminCashService>;
  let errorMock: jasmine.SpyObj<ErrorHandlerService>;

  const mockTable = (overrides: Partial<CashTable> = {}): CashTable => ({
    id: 't1', name: 'NL Hold\'em', asset: 'USDT_TRC20',
    smallBlind: 0.5, bigBlind: 1, minBuyIn: 20, maxBuyIn: 200,
    maxSeats: 6, rakeBasisPoints: 500, rakeCap: 3, seatedPlayers: 0, active: true,
    ...overrides
  });

  beforeEach(async () => {
    serviceMock = jasmine.createSpyObj('AdminCashService', ['create', 'list']);
    serviceMock.list.and.returnValue(of([]));
    serviceMock.create.and.returnValue(of(mockTable()));
    errorMock = jasmine.createSpyObj('ErrorHandlerService', ['addSuccess', 'addError']);

    await TestBed.configureTestingModule({
      imports: [AdminCashComponent],
      providers: [
        { provide: AdminCashService, useValue: serviceMock },
        { provide: ErrorHandlerService, useValue: errorMock }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminCashComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the active tables on init', () => {
    expect(serviceMock.list).toHaveBeenCalled();
  });

  it('renders active tables', () => {
    serviceMock.list.and.returnValue(of([mockTable({ name: 'High Roller' })]));
    component.refresh();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('[data-cy="ct-row"]');
    expect(rows.length).toBe(1);
    expect(fixture.nativeElement.querySelector('[data-cy="ct-list"]').textContent).toContain('High Roller');
  });

  describe('canCreate', () => {
    it('is false for a blank name', () => {
      component.form.name = '';
      expect(component.canCreate()).toBe(false);
    });

    it('is false when the big blind is not greater than the small blind', () => {
      component.form.name = 'Table';
      component.form.smallBlind = 1;
      component.form.bigBlind = 1;
      expect(component.canCreate()).toBe(false);
    });

    it('is false when max buy-in is below min buy-in', () => {
      component.form.name = 'Table';
      component.form.minBuyIn = 200;
      component.form.maxBuyIn = 100;
      expect(component.canCreate()).toBe(false);
    });

    it('is true for a valid configuration', () => {
      component.form.name = 'Nightly NL';
      expect(component.canCreate()).toBe(true);
    });
  });

  it('creates a table, reports success, and reloads the list', () => {
    component.form.name = 'Nightly NL';
    component.create();

    expect(serviceMock.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'Nightly NL' }));
    expect(errorMock.addSuccess).toHaveBeenCalled();
    // list() called once on init + once after create
    expect(serviceMock.list).toHaveBeenCalledTimes(2);
  });

  it('does not call the service when the form is invalid', () => {
    component.form.name = '';
    component.create();
    expect(serviceMock.create).not.toHaveBeenCalled();
  });
});
