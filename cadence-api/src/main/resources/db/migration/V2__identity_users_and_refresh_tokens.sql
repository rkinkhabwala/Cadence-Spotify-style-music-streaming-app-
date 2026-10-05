-- Owner: identity.

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(320) NOT NULL,           -- stored lower-cased
    password_hash varchar(100) NOT NULL,           -- BCrypt(12)
    display_name  varchar(100) NOT NULL,
    avatar_url    varchar(2048),
    country       varchar(2),                      -- ISO 3166-1 alpha-2
    birth_date    date,
    plan          varchar(16)  NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'PREMIUM')),
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE user_roles (
    user_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    varchar(16) NOT NULL CHECK (role IN ('LISTENER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   uuid        NOT NULL,             -- all tokens rotated from one login
    token_hash  varchar(64) NOT NULL,             -- SHA-256 hex of the opaque token
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
