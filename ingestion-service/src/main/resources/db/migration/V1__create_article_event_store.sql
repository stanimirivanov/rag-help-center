create table article_events (
    event_id uuid primary key,
    tenant_id uuid not null,
    aggregate_id uuid not null,
    stream_version bigint not null check (stream_version > 0),
    event_type varchar(100) not null,
    schema_version integer not null check (schema_version > 0),
    payload jsonb not null,
    occurred_at timestamptz not null,
    unique (tenant_id, aggregate_id, stream_version)
);

create index article_events_stream_idx
    on article_events (tenant_id, aggregate_id, stream_version);
