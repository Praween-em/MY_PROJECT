export function formatExpiryDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

export function formatPlanLabel(planType) {
  if (planType === 'quarterly') return '3 Months';
  if (planType === 'monthly') return '1 Month';
  return planType || '—';
}
