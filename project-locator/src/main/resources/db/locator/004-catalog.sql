ALTER TABLE locator_projects
    ADD COLUMN description text NOT NULL DEFAULT '',
    ADD COLUMN readiness text NOT NULL DEFAULT 'development',
    ADD CONSTRAINT locator_description_length CHECK (length(description) <= 500),
    ADD CONSTRAINT locator_readiness CHECK (readiness IN ('planning', 'development'));
