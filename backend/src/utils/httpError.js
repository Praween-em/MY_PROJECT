/**
 * Public error bodies. Unexpected failures stay in the server log.
 */
function sendError(res, err, fallback = 'Request failed') {
  const declared = Number(err?.status);
  const intentional = Number.isInteger(declared) && declared >= 400 && declared < 600;
  const status = intentional ? declared : 500;
  console.error(intentional ? err.message : err);
  const message = intentional && err.message ? err.message : fallback;
  const body = { message };
  if (intentional && err.code) body.code = err.code;
  res.status(status).json(body);
}

module.exports = { sendError };
