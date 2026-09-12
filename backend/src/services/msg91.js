/**
 * msg91.js — server-side MSG91 OTP access-token verification
 *
 * Client OTPWidget.verifyOTP → JWT (usually in response.message)
 * Server confirms JWT via MSG91 verifyAccessToken using MSG91_AUTHKEY (env).
 */

const MSG91_VERIFY_URL = 'https://control.msg91.com/api/v5/widget/verifyAccessToken';

function getAuthKey() {
  const key = (process.env.MSG91_AUTHKEY || process.env.MSG91_AUTH_KEY || '').trim();
  if (!key) {
    throw new Error('MSG91_AUTHKEY is not configured on the server');
  }
  return key;
}

function isOk(data) {
  return (
    data?.type === 'success' ||
    data?.success === true ||
    (typeof data?.message === 'string' &&
      /success/i.test(data.message) &&
      data?.type !== 'error')
  );
}

async function postVerify(authkey, token, mode) {
  const headers = {
    Accept: 'application/json',
    authkey,
  };

  if (mode === 'json') {
    return fetch(MSG91_VERIFY_URL, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        authkey,
        'access-token': token,
        access_token: token,
      }),
    });
  }

  const form = new URLSearchParams();
  form.append('authkey', authkey);
  form.append('access-token', token);
  return fetch(MSG91_VERIFY_URL, {
    method: 'POST',
    headers: {
      ...headers,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: form.toString(),
  });
}

/**
 * @param {string} accessToken — JWT from client
 */
async function verifyAccessToken(accessToken) {
  if (!accessToken || typeof accessToken !== 'string') {
    const err = new Error('accessToken is required');
    err.status = 400;
    throw err;
  }

  const authkey = getAuthKey();
  const token = accessToken.trim().replace(/^Bearer\s+/i, '');

  if (!token || token.length < 20) {
    const err = new Error('accessToken looks empty or invalid');
    err.status = 400;
    throw err;
  }

  console.log('[msg91] verifying token len=', token.length, 'jwtParts=', token.split('.').length);

  // Prefer JSON (MSG91 dashboard snippet). Fall back to form if needed.
  let res = await postVerify(authkey, token, 'json');
  let data = await res.json().catch(() => ({}));
  console.log('[msg91] json attempt status=', res.status, 'type=', data?.type, 'message=', data?.message);

  if (
    data?.type === 'error' &&
    typeof data?.message === 'string' &&
    (/access-token field is required/i.test(data.message) || /authenticationfailure/i.test(data.message))
  ) {
    res = await postVerify(authkey, token, 'form');
    data = await res.json().catch(() => ({}));
    console.log('[msg91] form attempt status=', res.status, 'type=', data?.type, 'message=', data?.message);
  }

  if (!isOk(data)) {
    const msg =
      (typeof data.message === 'string' && data.message) ||
      data.error ||
      'OTP access token verification failed';

    let hint = '';
    if (/auth/i.test(msg) && !/access-token/i.test(msg)) {
      hint =
        ' Check MSG91_AUTHKEY is the SuperRIdex Authkey (not widget token) and IP security allows this server.';
    }

    const err = new Error(msg + hint);
    err.status = 401;
    err.data = data;
    throw err;
  }

  return { ok: true, data };
}

module.exports = {
  verifyAccessToken,
};
