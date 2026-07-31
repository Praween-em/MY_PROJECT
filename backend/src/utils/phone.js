/**
 * Normalize Indian mobile numbers to 10 digits (strips +91 / spaces).
 */
function normalizePhone(raw) {
  const digits = String(raw || '').replace(/\D/g, '');
  if (digits.length >= 10) return digits.slice(-10);
  return null;
}

module.exports = { normalizePhone };
