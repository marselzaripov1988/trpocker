import { checkOk, get, parseJson, post, apiBase } from './http.js';

const PASSWORD = __ENV.K6_PASSWORD || 'LoadTest123!';

export function registerAndLogin(username) {
  const api = apiBase();

  const registerRes = post(`${api}/auth/register`, {
    username,
    email: `${username}@k6.local`,
    password: PASSWORD,
  }, null, { name: 'auth_register' });

  checkOk(registerRes, 'auth_register', [200, 201]);

  const loginRes = post(`${api}/auth/login`, {
    username,
    password: PASSWORD,
  }, null, { name: 'auth_login' });

  checkOk(loginRes, 'auth_login', [200]);
  const body = parseJson(loginRes);
  return {
    token: body?.accessToken,
    username,
  };
}

/** Log in an existing account (e.g. the seeded admin) without registering it. */
export function login(username, password) {
  const api = apiBase();
  const res = post(`${api}/auth/login`, { username, password }, null, { name: 'auth_login' });
  checkOk(res, 'auth_login', [200]);
  const body = parseJson(res);
  return { token: body?.accessToken, username };
}

export function fetchProfile(token) {
  const api = apiBase();
  const res = get(`${api}/v1/users/profile`, token, { name: 'users_profile' });
  checkOk(res, 'users_profile', [200]);
  return parseJson(res);
}
