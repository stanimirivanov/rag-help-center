alter table article_events
    add column recorded_at timestamptz not null default clock_timestamp(),
    add constraint article_events_event_type_not_blank check (event_type <> ''),
    add constraint article_events_payload_is_object check (jsonb_typeof(payload) = 'object');
