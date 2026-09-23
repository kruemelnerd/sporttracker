CREATE TABLE foundation_schema (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT foundation_schema_single_row CHECK (id)
);

INSERT INTO foundation_schema (id) VALUES (TRUE);
