import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PlayerAvatarService } from './player-avatar.service';

describe('PlayerAvatarService', () => {
  let service: PlayerAvatarService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PlayerAvatarService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(PlayerAvatarService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches missing avatars and exposes them via the signal', () => {
    service.ensure(['u1', 'u2', null, undefined]);
    const req = httpMock.expectOne(r => r.url === '/api/v1/users/avatars');
    expect(req.request.params.get('ids')).toBe('u1,u2');
    req.flush({ u1: '🦊' });

    expect(service.avatarFor('u1')).toBe('🦊');
    expect(service.avatarFor('u2')).toBe(''); // fetched, none set
    expect(service.avatarFor(null)).toBe('');
  });

  it('does not refetch already-cached or in-flight ids', () => {
    service.ensure(['u1']);
    httpMock.expectOne(r => r.url === '/api/v1/users/avatars').flush({ u1: '🐼' });

    // u1 cached, u3 new → only u3 is requested
    service.ensure(['u1', 'u3']);
    const req = httpMock.expectOne(r => r.url === '/api/v1/users/avatars');
    expect(req.request.params.get('ids')).toBe('u3');
    req.flush({ u3: '🐲' });

    expect(service.avatarFor('u1')).toBe('🐼');
    expect(service.avatarFor('u3')).toBe('🐲');
  });

  it('makes no request when there are no resolvable ids', () => {
    service.ensure([null, undefined]);
    httpMock.expectNone(() => true);
  });
});
