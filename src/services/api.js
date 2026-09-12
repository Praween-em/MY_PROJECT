/**
 * api.js — HTTP client for AG rider backend (Postgres / Railway)
 */

import Constants from 'expo-constants';
import { getDeviceId, getDeviceLabel } from '../utils/deviceId';

function resolveBaseUrl() {
  const fromExtra = Constants.expoConfig?.extra?.apiUrl;
  const fromEnv = process.env.EXPO_PUBLIC_API_URL;
  let url = String(fromExtra || fromEnv || '').trim();
  // Guard against .env mistakes like: EXPO_PUBLIC_API_URL=EXPO_PUBLIC_API_URL=https://...
  url = url.replace(/^EXPO_PUBLIC_API_URL=/i, '').trim();
  url = url.replace(/\/$/, '');
  if (url && !/^https?:\/\//i.test(url)) {
    url = `https://${url}`;
  }
  // Production Railway backend (admin panel + DB social links live here)
  return url || 'https://myproject-production-e2d4.up.railway.app';
}

const BASE_URL = resolveBaseUrl();

if (__DEV__) {
  console.log('[api] BASE_URL =', BASE_URL);
}

export function getApiBaseUrl() {
  return BASE_URL;
}

export class ApiError extends Error {
  constructor(message, { status, code, data } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.data = data;
  }
}

async function request(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const deviceId = await getDeviceId();
  const deviceLabel = await getDeviceLabel();
  if (deviceId) headers['x-device-id'] = deviceId;
  if (deviceLabel) headers['x-device-label'] = deviceLabel;

  const payload = body
    ? { ...body, deviceId: body.deviceId || deviceId, deviceLabel: body.deviceLabel || deviceLabel }
    : undefined;

  // Always send phone in headers when present (backend auth reads body / Bearer / x-phone)
  const phone = payload?.phone || body?.phone;
  if (phone) {
    const digits = String(phone).replace(/\D/g, '').slice(-10);
    if (digits.length === 10) {
      headers['x-phone'] = digits;
      if (!token) headers.Authorization = `Bearer ${digits}`;
    }
  }

  const url = `${BASE_URL}${path}`;
  if (__DEV__) {
    console.log(`[api] ${method} ${url}`);
  }

  let res;
  try {
    res = await fetch(url, {
      method,
      headers,
      body: payload ? JSON.stringify(payload) : undefined,
    });
  } catch (err) {
    throw new ApiError(
      `Cannot reach backend (${BASE_URL}). Check EXPO_PUBLIC_API_URL and rebuild the app.`,
      { status: 0, code: 'NETWORK', data: { cause: err?.message } }
    );
  }

  const raw = await res.text();
  let data = {};
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch {
    if (/application failed to respond/i.test(raw) || res.status >= 502) {
      throw new ApiError(
        'Backend is not running. Open Railway logs and confirm /health returns JSON.',
        { status: res.status, code: 'BACKEND_DOWN' }
      );
    }
    data = { message: raw.slice(0, 160) || 'Request failed' };
  }
  if (!res.ok) {
    throw new ApiError(data.message || 'Request failed', {
      status: res.status,
      code: data.code,
      data,
    });
  }
  return data;
}

export async function getSubscriptionPlans() {
  return request('GET', '/subscription/plans');
}

export async function getSubscriptionStatus(phone) {
  const deviceId = await getDeviceId();
  const qs = new URLSearchParams({ phone });
  if (deviceId) qs.set('deviceId', deviceId);
  return request('GET', `/subscription/status?${qs.toString()}`);
}

export async function checkEntitlement(phone) {
  return request('POST', '/entitlement/check', { phone });
}

export async function getReferralStatus(phone) {
  return request('GET', `/referral/status?phone=${encodeURIComponent(phone)}`);
}

export async function applyReferralCode(phone, code) {
  return request('POST', '/referral/apply', { phone, code });
}

export async function registerUser(phone, referralCode) {
  return request('POST', '/referral/register', { phone, referralCode });
}

/**
 * After client MSG91 verifyOTP, confirm JWT on our server and register user.
 */
export async function verifyOtpWithServer({ phone, accessToken, referralCode }) {
  return request('POST', '/auth/verify-otp', {
    phone,
    accessToken,
    'access-token': accessToken,
    referralCode,
  });
}

/** Public social / help links from DB (admin-editable). */
export async function getSocialLinks() {
  return request('GET', '/socials');
}

/** Backend-managed Telegram payment destination and optional plans-screen image. */
export async function getPaymentContact() {
  const data = await request('GET', '/payment-contact');
  return {
    ...data,
    imageUrl: data.imagePath ? `${BASE_URL}${data.imagePath}` : null,
  };
}
