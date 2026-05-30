-- Anvil credentials store. One row per stored secret.
--
-- value_enc is base64(IV || AES-GCM(value)).  IV is the 12-byte nonce
-- prefixed onto the ciphertext + auth tag.  Keys are derived from
-- ANVIL_SECRET_KEY env var or, failing that, a per-installation key
-- file at ~/.config/anvil/master.key (created 0600 on first use).
--
-- masked_value is a display-safe representation (e.g. last 4 chars +
-- '****' prefix) shown in the admin UI without ever revealing the
-- secret itself.
--
-- credential_type matches Jenkins's credential kinds: 'string',
-- 'username-password', 'ssh-private-key', 'file', 'certificate'.
-- v1 ships :string + :username-password; the others land as the use
-- case appears.
CREATE TABLE IF NOT EXISTS anvil_credentials (
  id              TEXT PRIMARY KEY,
  credential_type TEXT NOT NULL DEFAULT 'string',
  description     TEXT,
  value_enc       TEXT NOT NULL,
  masked_value    TEXT NOT NULL DEFAULT '***',
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
