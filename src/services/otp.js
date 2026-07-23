/**
 * otp.js — MSG91 OTP Widget (client-side SDK)
 *
 * OTP only: initializeWidget / sendOTP / retryOTP / verifyOTP.
 * Do not import BiometricAuth from this package — not used in this app.
 *
 * Requires EXPO_PUBLIC_MSG91_WIDGET_ID + EXPO_PUBLIC_MSG91_AUTH_TOKEN
 * Enable "Mobile Integration" on the MSG91 widget in the dashboard.
 *
 * MSG91 response shapes (from SDK types):
 *   sendOTP success  → { type: 'success', message: '<reqId>' }
 *   verifyOTP success → { type: 'success', 'access-token': '<jwt>' }
 *                       (some widgets also put the JWT in message)
 */

import { OTPWidget } from '@msg91comm/sendotp-react-native';

const WIDGET_ID = process.env.EXPO_PUBLIC_MSG91_WIDGET_ID || '';
const AUTH_TOKEN = process.env.EXPO_PUBLIC_MSG91_AUTH_TOKEN || '';

let initialized = false;

export function isOtpConfigured() {
  return !!(WIDGET_ID && AUTH_TOKEN);
}

export function initOtpWidget() {
  if (!isOtpConfigured()) {
    console.warn('[otp] MSG91 widgetId / authToken not set in env');
    return false;
  }
  if (initialized) return true;
  OTPWidget.initializeWidget(String(WIDGET_ID), String(AUTH_TOKEN));
  initialized = true;
  return true;
}

/** India mobile → identifier without + (e.g. 919876543210) */
export function toOtpIdentifier(phone10) {
  const digits = String(phone10 || '').replace(/\D/g, '');
  if (digits.length === 10) return `91${digits}`;
  if (digits.startsWith('91') && digits.length === 12) return digits;
  return digits;
}

function looksLikeJwt(value) {
  if (typeof value !== 'string') return false;
  const parts = value.split('.');
  return parts.length >= 3 && value.length > 40;
}

function extractReqId(response) {
  if (!response) return null;
  if (typeof response.message === 'string' && !looksLikeJwt(response.message)) {
    return response.message;
  }
  return (
    response.reqId ||
    response.requestId ||
    response.message?.reqId ||
    null
  );
}

/**
 * MSG91 puts the JWT on `access-token`, or as a JWT string in `message` (verify only).
 * Do NOT treat every success `message` as a token — sendOTP stores reqId in `message`.
 */
function extractAccessToken(response) {
  if (!response) return null;

  const candidates = [
    response['access-token'],
    response.access_token,
    response.accessToken,
    response.token,
    response.data?.['access-token'],
    response.data?.accessToken,
    typeof response.message === 'object' && response.message
      ? (response.message['access-token'] || response.message.accessToken)
      : null,
  ];

  for (const c of candidates) {
    if (typeof c === 'string' && c.trim().length > 10) return c.trim();
  }

  // verifyOTP success often returns the JWT as message (must look like JWT, not a reqId)
  if (typeof response.message === 'string' && looksLikeJwt(response.message)) {
    return response.message.trim();
  }

  return null;
}

function isSuccess(response) {
  if (!response) return false;
  if (response.type === 'success' || response.success === true) return true;
  if (response.type === 'error' || response.success === false) return false;
  if (extractAccessToken(response) || extractReqId(response)) return true;
  return false;
}

function errorMessage(response, fallback) {
  if (!response) return fallback;
  if (typeof response.message === 'string' && !looksLikeJwt(response.message)) {
    return response.message;
  }
  if (response.message?.message) return response.message.message;
  if (response.error) return String(response.error);
  return fallback;
}

export async function sendOtp(phone10) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured. Add MSG91 widget ID and auth token.');
  }
  const identifier = toOtpIdentifier(phone10);
  if (identifier.length < 12) {
    throw new Error('Enter a valid 10-digit mobile number');
  }
  const response = await OTPWidget.sendOTP({ identifier });
  if (__DEV__) {
    console.log('[otp] sendOTP type=', response?.type, 'messageLen=', String(response?.message || '').length);
  }

  // Invisible verification may return a real JWT access-token immediately
  const earlyToken = extractAccessToken(response);
  if (earlyToken && looksLikeJwt(earlyToken)) {
    return { reqId: extractReqId(response), accessToken: earlyToken, response, alreadyVerified: true };
  }

  if (!isSuccess(response)) {
    throw new Error(errorMessage(response, 'Failed to send OTP'));
  }
  const reqId = extractReqId(response);
  if (!reqId) {
    throw new Error('OTP sent but request id missing. Check MSG91 widget config.');
  }
  return { reqId, accessToken: null, response, alreadyVerified: false };
}

export async function retryOtp(reqId, retryChannel) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured');
  }
  if (!reqId) throw new Error('Missing OTP request id');
  const body = { reqId };
  if (retryChannel != null) body.retryChannel = retryChannel;
  const response = await OTPWidget.retryOTP(body);
  if (!isSuccess(response)) {
    throw new Error(errorMessage(response, 'Failed to resend OTP'));
  }
  return { reqId: extractReqId(response) || reqId, response };
}

export async function verifyOtp(reqId, otp) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured');
  }
  if (!reqId) throw new Error('Missing OTP request id — send OTP again');
  const code = String(otp || '').trim();
  if (code.length < 4) throw new Error('Enter the OTP you received');

  const response = await OTPWidget.verifyOTP({ reqId, otp: code });
  if (__DEV__) {
    console.log('[otp] verifyOTP raw:', JSON.stringify(response));
  }

  if (!isSuccess(response)) {
    throw new Error(errorMessage(response, 'Invalid OTP'));
  }

  const accessToken = extractAccessToken(response);
  if (!accessToken) {
    throw new Error(
      'OTP verified but access token missing from MSG91 response. ' +
      'Ensure Mobile Integration is enabled on the widget.'
    );
  }

  return { accessToken, response };
}
