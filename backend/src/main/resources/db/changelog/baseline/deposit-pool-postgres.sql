CREATE TABLE public.deposit_address_pool (
    id uuid NOT NULL,
    address character varying(128) NOT NULL,
    asset character varying(32) NOT NULL,
    assigned_at timestamp(6) with time zone,
    assigned_user_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    derivation_index bigint NOT NULL,
    status character varying(16) NOT NULL,
    version bigint,
    CONSTRAINT deposit_address_pool_asset_check CHECK (((asset)::text = ANY ((ARRAY['USDT_TRC20'::character varying, 'USDT_ERC20'::character varying, 'BTC'::character varying, 'ETH'::character varying, 'LTC'::character varying])::text[]))),
    CONSTRAINT deposit_address_pool_status_check CHECK (((status)::text = ANY ((ARRAY['FREE'::character varying, 'ASSIGNED'::character varying])::text[])))
);
ALTER TABLE ONLY public.deposit_address_pool ADD CONSTRAINT deposit_address_pool_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.deposit_address_pool ADD CONSTRAINT uk_pool_asset_address UNIQUE (asset, address);
ALTER TABLE ONLY public.deposit_address_pool ADD CONSTRAINT uk_pool_asset_user UNIQUE (asset, assigned_user_id);
CREATE INDEX idx_pool_asset_status ON public.deposit_address_pool (asset, status);
