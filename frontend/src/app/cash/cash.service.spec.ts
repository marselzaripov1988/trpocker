import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { CashService } from './cash.service';
import { environment } from '../../environments/environment';

describe('CashService', () => {
  let service: CashService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/v1/cash/tables`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CashService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(CashService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sit posts the buy-in', () => {
    service.sit('t1', 10).subscribe();
    const req = httpMock.expectOne(`${base}/t1/sit`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ buyIn: 10 });
    req.flush({ seatNumber: 0, playerName: 'A', stack: 10, status: 'ACTIVE' });
  });

  it('topUp posts the amount to the top-up endpoint', () => {
    service.topUp('t1', 5).subscribe();
    const req = httpMock.expectOne(`${base}/t1/top-up`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ amount: 5 });
    req.flush({ seatNumber: 0, playerName: 'A', stack: 15, status: 'ACTIVE' });
  });

  it('leave posts to the leave endpoint', () => {
    service.leave('t1').subscribe();
    const req = httpMock.expectOne(`${base}/t1/leave`);
    expect(req.request.method).toBe('POST');
    req.flush({ cashedOutNow: true, amount: 15 });
  });
});
