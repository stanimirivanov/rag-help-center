create table article_projection (
    tenant_id uuid not null,
    article_id uuid not null,
    title varchar(200) not null,
    body text not null,
    locale varchar(16) not null,
    lifecycle_status varchar(20) not null,
    revision bigint not null check (revision >= 0),
    stream_version bigint not null check (stream_version > 0),
    indexing_status varchar(20) not null default 'NOT_REQUESTED',
    updated_at timestamptz not null,
    primary key (tenant_id, article_id),
    check (lifecycle_status in ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    check (indexing_status in ('NOT_REQUESTED', 'PENDING', 'INDEXED', 'FAILED'))
);

create table article_outbox (
    outbox_id uuid primary key,
    tenant_id uuid not null,
    aggregate_id uuid not null,
    stream_version bigint not null,
    event_type varchar(100) not null,
    schema_version integer not null check (schema_version > 0),
    trace_id varchar(64) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz not null,
    recorded_at timestamptz not null default clock_timestamp(),
    published_at timestamptz,
    unique (tenant_id, aggregate_id, stream_version)
);

create index article_outbox_pending_idx on article_outbox (recorded_at) where published_at is null;

create table command_idempotency (
    tenant_id uuid not null,
    idempotency_key varchar(200) not null,
    command_type varchar(100) not null,
    request_hash varchar(64) not null,
    article_id uuid not null,
    stream_version bigint not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (tenant_id, idempotency_key)
);
