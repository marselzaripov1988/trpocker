import http from 'k6/http';
import { check } from 'k6';

export function apiBase() {
  const base = __ENV.BASE_URL || 'http://localhost:8080';
  return `${base.replace(/\/$/, '')}/api`;
}

export function jsonHeaders(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers };
}

export function parseJson(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

export function checkOk(res, name, expected = [200, 201, 202, 204]) {
  return check(res, {
    [`${name} status`]: (r) => expected.includes(r.status),
  });
}

export function get(path, token, tags = {}) {
  return http.get(path, { ...jsonHeaders(token), tags });
}

export function post(path, body, token, tags = {}) {
  const payload = body === null || body === undefined ? null : JSON.stringify(body);
  return http.post(path, payload, { ...jsonHeaders(token), tags });
}
