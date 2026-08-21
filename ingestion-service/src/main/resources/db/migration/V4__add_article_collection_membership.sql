alter table article_projection add column collection_id uuid;

create index article_projection_collection_idx
    on article_projection (tenant_id, collection_id, article_id);
