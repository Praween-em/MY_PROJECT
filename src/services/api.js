/**
 * api.js — HTTP client for SUPER RIDEX backend (Postgres / Railway)
 */

import { getDeviceId, getDeviceLabel } from '../utils/deviceId';

const BASE_URL = process.env.EXPO_PUBLIC_API_URL || 'https://api.playnix.in';

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

  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: payload ? JSON.stringify(payload) : undefined,
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new ApiError(data.message || 'Request failed', {
      status: res.status,
      code: data.code,
      data,
    });
  }
  return data;
}

export async function createOrder(planId, phone) {
  return request('POST', '/subscription/create-order', { planId, phone });
}

export async function verifyPayment({ razorpayOrderId, razorpayPaymentId, razorpaySignature, phone, planId }) {
  return request('POST', '/subscription/verify-payment', {
    razorpayOrderId,
    razorpayPaymentId,
    razorpaySignature,
    phone,
    planId,
  });
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
