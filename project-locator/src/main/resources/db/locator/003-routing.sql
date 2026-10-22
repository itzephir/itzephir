ALTER TABLE locator_projects
    ADD COLUMN routing_mode text NOT NULL DEFAULT 'legacy',
    ADD COLUMN routing_target text NOT NULL DEFAULT '',
    ADD CONSTRAINT locator_routing_mode CHECK (routing_mode IN ('legacy', 'docker', 'proxy', 'redirect')),
    ADD CONSTRAINT locator_routing_target CHECK (
        (routing_mode = 'legacy' AND routing_target = '') OR
        (routing_mode <> 'legacy' AND length(routing_target) BETWEEN 1 AND 2048)
    );
