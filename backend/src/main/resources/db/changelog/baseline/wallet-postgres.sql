-- Wallet subsystem schema (Postgres). Generated from the JPA entities (WalletAccount, WalletLedgerEntry,
-- WithdrawalRequest, KycRecord) via Hibernate ddl-auto=create + pg_dump, like the baseline. Added after
-- the squashed baseline; keeps the schema in lockstep with the entities so ddl-auto=validate passes.

CREATE TABLE public.kyc_records (
    id uuid NOT NULL,
    provider character varying(64),
    provider_ref character varying(128),
    status character varying(32) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT kyc_records_status_check CHECK (((status)::text = ANY ((ARRAY['NONE'::character varying, 'PENDING'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying])::text[])))
);
CREATE TABLE public.wallet_accounts (
    id uuid NOT NULL,
    asset character varying(32) NOT NULL,
    balance numeric(38,18) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    version bigint,
    CONSTRAINT wallet_accounts_asset_check CHECK (((asset)::text = ANY ((ARRAY['USDT_TRC20'::character varying, 'USDT_ERC20'::character varying, 'BTC'::character varying, 'ETH'::character varying, 'LTC'::character varying])::text[])))
);
CREATE TABLE public.wallet_ledger_entries (
    id uuid NOT NULL,
    amount numeric(38,18) NOT NULL,
    asset character varying(32) NOT NULL,
    balance_after numeric(38,18) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    external_tx_id character varying(128),
    type character varying(32) NOT NULL,
    user_id uuid NOT NULL,
    withdrawal_id uuid,
    CONSTRAINT wallet_ledger_entries_asset_check CHECK (((asset)::text = ANY ((ARRAY['USDT_TRC20'::character varying, 'USDT_ERC20'::character varying, 'BTC'::character varying, 'ETH'::character varying, 'LTC'::character varying])::text[]))),
    CONSTRAINT wallet_ledger_entries_type_check CHECK (((type)::text = ANY ((ARRAY['DEPOSIT'::character varying, 'WITHDRAWAL'::character varying, 'WITHDRAWAL_REVERSAL'::character varying])::text[])))
);
CREATE TABLE public.withdrawal_requests (
    id uuid NOT NULL,
    amount numeric(38,18) NOT NULL,
    asset character varying(32) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    status character varying(32) NOT NULL,
    to_address character varying(128) NOT NULL,
    tx_id character varying(128),
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    version bigint,
    CONSTRAINT withdrawal_requests_asset_check CHECK (((asset)::text = ANY ((ARRAY['USDT_TRC20'::character varying, 'USDT_ERC20'::character varying, 'BTC'::character varying, 'ETH'::character varying, 'LTC'::character varying])::text[]))),
    CONSTRAINT withdrawal_requests_status_check CHECK (((status)::text = ANY ((ARRAY['APPROVED'::character varying, 'BROADCAST'::character varying, 'CONFIRMED'::character varying, 'FAILED'::character varying])::text[])))
);
ALTER TABLE ONLY public.kyc_records
    ADD CONSTRAINT kyc_records_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.kyc_records
    ADD CONSTRAINT uq_kyc_user UNIQUE (user_id);
ALTER TABLE ONLY public.wallet_accounts
    ADD CONSTRAINT uq_wallet_account_user_asset UNIQUE (user_id, asset);
ALTER TABLE ONLY public.wallet_ledger_entries
    ADD CONSTRAINT uq_wallet_ledger_external_tx UNIQUE (external_tx_id);
ALTER TABLE ONLY public.wallet_accounts
    ADD CONSTRAINT wallet_accounts_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.wallet_ledger_entries
    ADD CONSTRAINT wallet_ledger_entries_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.withdrawal_requests
    ADD CONSTRAINT withdrawal_requests_pkey PRIMARY KEY (id);
CREATE INDEX idx_wallet_ledger_user_asset ON public.wallet_ledger_entries USING btree (user_id, asset);
CREATE INDEX idx_withdrawal_user ON public.withdrawal_requests USING btree (user_id);
