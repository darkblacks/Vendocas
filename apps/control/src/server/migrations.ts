/**
 * Control Center schema. Kept as plain strings (no imports) so it can be applied both by the app at cold
 * start (advisory-locked) and by `scripts/migrate.ts`. Append-only: never edit an applied migration.
 */
export const MIGRATIONS: { id: number; name: string; sql: string }[] = [
  {
    id: 1,
    name: "init",
    sql: `
CREATE TABLE IF NOT EXISTS cc_system_state (
  id                    smallint PRIMARY KEY CHECK (id = 1),
  bootstrap_completed_at timestamptz,
  bootstrap_owner_id    uuid,
  selected_codespace    text,
  github_repository     text,
  max_session_hours     numeric(4,2) NOT NULL DEFAULT 6 CHECK (max_session_hours > 0 AND max_session_hours <= 6),
  updated_at            timestamptz NOT NULL DEFAULT now()
);
INSERT INTO cc_system_state (id) VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS cc_users (
  id               uuid PRIMARY KEY,
  email            text NOT NULL UNIQUE,
  display_name     text NOT NULL,
  password_hash    text NOT NULL,
  role             text NOT NULL CHECK (role IN ('OWNER','ADMIN','VIEWER')),
  status           text NOT NULL CHECK (status IN ('PENDING_MFA','ACTIVE','DISABLED')),
  mfa_secret_enc   text,
  mfa_enabled_at   timestamptz,
  failed_logins    int NOT NULL DEFAULT 0,
  locked_until     timestamptz,
  created_by       uuid,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cc_recovery_codes (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES cc_users(id) ON DELETE CASCADE,
  code_hash   text NOT NULL,
  used_at     timestamptz
);
CREATE INDEX IF NOT EXISTS ix_cc_recovery_user ON cc_recovery_codes(user_id);

-- Pre-session challenges (password ok, waiting for TOTP) and MFA enrollment.
CREATE TABLE IF NOT EXISTS cc_auth_challenges (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES cc_users(id) ON DELETE CASCADE,
  purpose     text NOT NULL CHECK (purpose IN ('MFA_LOGIN','MFA_ENROLL')),
  token_hash  text NOT NULL UNIQUE,
  pending_secret_enc text,
  attempts    int NOT NULL DEFAULT 0,
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cc_sessions (
  id            uuid PRIMARY KEY,
  user_id       uuid NOT NULL REFERENCES cc_users(id) ON DELETE CASCADE,
  token_hash    text NOT NULL UNIQUE,
  csrf_token    text NOT NULL,
  ip            text,
  user_agent    text,
  reauth_at     timestamptz NOT NULL,
  expires_at    timestamptz NOT NULL,
  idle_expires_at timestamptz NOT NULL,
  revoked_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_cc_sessions_user ON cc_sessions(user_id);

CREATE TABLE IF NOT EXISTS cc_rate_limits (
  bucket       text NOT NULL,
  window_start timestamptz NOT NULL,
  hits         int NOT NULL,
  PRIMARY KEY (bucket, window_start)
);

CREATE TABLE IF NOT EXISTS cc_audit_events (
  id          bigserial PRIMARY KEY,
  actor_id    uuid,
  actor_label text NOT NULL,
  action      text NOT NULL,
  target      text,
  outcome     text NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE','DENIED')),
  detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
  ip          text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_cc_audit_created ON cc_audit_events(created_at DESC);

CREATE TABLE IF NOT EXISTS integration_credentials (
  id                     uuid PRIMARY KEY,
  provider               text NOT NULL,
  environment            text NOT NULL CHECK (environment IN ('TEST','LIVE')),
  encrypted_payload      text NOT NULL,
  nonce                  text NOT NULL,
  encryption_key_version text NOT NULL,
  masked_hint            jsonb NOT NULL DEFAULT '{}'::jsonb,
  status                 text NOT NULL CHECK (status IN ('UNTESTED','VALID','INVALID','DISABLED','REVOKED')),
  last_tested_at         timestamptz,
  last_error_code        text,
  last_error_message     text,
  created_by             uuid,
  updated_by             uuid,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                int NOT NULL DEFAULT 1
);
-- One live credential per provider and environment; disabled/revoked ones are kept for history.
CREATE UNIQUE INDEX IF NOT EXISTS ux_integration_live ON integration_credentials(provider, environment)
  WHERE status NOT IN ('DISABLED','REVOKED');

CREATE TABLE IF NOT EXISTS integration_credential_history (
  id            bigserial PRIMARY KEY,
  credential_id uuid NOT NULL,
  provider      text NOT NULL,
  environment   text NOT NULL,
  action        text NOT NULL,
  actor_id      uuid,
  actor_label   text NOT NULL,
  detail        jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_cc_cred_hist ON integration_credential_history(provider, created_at DESC);

CREATE TABLE IF NOT EXISTS cc_codespace_sessions (
  id              uuid PRIMARY KEY,
  codespace_name  text NOT NULL,
  started_by      uuid,
  state           text NOT NULL CHECK (state IN ('STARTING','RUNNING','STOP_REQUESTED','STOPPED','ENDED_EXTERNALLY','FAILED')),
  requested_at    timestamptz NOT NULL DEFAULT now(),
  running_at      timestamptz,
  deadline_at     timestamptz NOT NULL,
  stop_reason     text,
  stop_requested_at timestamptz,
  stop_attempts   int NOT NULL DEFAULT 0,
  next_check_at   timestamptz NOT NULL DEFAULT now(),
  last_checked_at timestamptz,
  last_github_state text,
  last_error      text,
  ended_at        timestamptz,
  idempotency_key text UNIQUE,
  lease_until     timestamptz
);
-- At most one open session per codespace (prevents double-click / concurrent starts).
CREATE UNIQUE INDEX IF NOT EXISTS ux_cc_session_open ON cc_codespace_sessions(codespace_name)
  WHERE state IN ('STARTING','RUNNING','STOP_REQUESTED');

CREATE TABLE IF NOT EXISTS cc_operation_log (
  id          bigserial PRIMARY KEY,
  category    text NOT NULL,
  level       text NOT NULL CHECK (level IN ('INFO','WARN','ERROR')),
  message     text NOT NULL,
  detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_cc_oplog ON cc_operation_log(created_at DESC);
`,
  },
];
