-- UPI id and WhatsApp group used after a plan is selected.
ALTER TABLE payment_contact_config
  ADD COLUMN IF NOT EXISTS upi_id TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS payee_name TEXT NOT NULL DEFAULT 'AG Rider',
  ADD COLUMN IF NOT EXISTS whatsapp_group_url TEXT NOT NULL DEFAULT '';
