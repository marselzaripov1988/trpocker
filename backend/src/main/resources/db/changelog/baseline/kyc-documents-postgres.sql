CREATE TABLE public.kyc_documents (
    id uuid NOT NULL,
    content_type character varying(128) NOT NULL,
    original_filename character varying(256),
    sha256 character varying(64) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_key character varying(128) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL
);
ALTER TABLE ONLY public.kyc_documents ADD CONSTRAINT kyc_documents_pkey PRIMARY KEY (id);
CREATE INDEX idx_kyc_doc_user ON public.kyc_documents (user_id);
