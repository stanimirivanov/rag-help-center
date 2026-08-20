create extension if not exists vector;

create table embedding_inbox (
    event_id uuid primary key,
    event_type varchar(100) not null,
    received_at timestamptz not null default clock_timestamp()
);

create table article_chunks (
    chunk_id uuid primary key,
    tenant_id uuid not null,
    article_id uuid not null,
    revision bigint not null check (revision > 0),
    chunk_index integer not null check (chunk_index >= 0),
    locale varchar(16) not null,
    content text not null,
    embedding vector not null,
    active boolean not null default true,
    indexed_at timestamptz not null default clock_timestamp(),
    unique (tenant_id, article_id, revision, chunk_index)
);

create index article_chunks_lookup_idx on article_chunks (tenant_id, article_id, revision) where active;

create table embedding_status_outbox (
    status_event_id uuid primary key,
    source_event_id uuid not null unique,
    tenant_id uuid not null,
    article_id uuid not null,
    revision bigint not null,
    status varchar(20) not null check (status in ('INDEXED', 'WITHDRAWN', 'FAILED')),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    recorded_at timestamptz not null default clock_timestamp(),
    published_at timestamptz
);
