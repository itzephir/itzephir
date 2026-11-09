-- The existing minor-web deployment owns its rotating container ports and keeps
-- its current route behind these stable, loopback-only gateways. Only public
-- entry paths are retired; the project keeps ownership of application releases.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM locator_projects
        WHERE project = 'minor-website' AND slug = 'minor-web'
          AND routing_mode = 'legacy' AND routing_target = ''
    ) AND current_setting('itzephir.locator_environment') NOT IN ('dev', 'prod') THEN
        RAISE EXCEPTION 'Set LOCATOR_ENVIRONMENT to dev or prod before migrating existing minor-web routes';
    END IF;
END;
$migration$;

UPDATE locator_projects
SET routing_mode = 'proxy',
    routing_target = CASE current_setting('itzephir.locator_environment')
        WHEN 'dev' THEN 'http://127.0.0.1:18101/dev/project/minor-web'
        WHEN 'prod' THEN 'http://127.0.0.1:18100/dev/project/minor-web'
    END,
    revision = revision + 1
WHERE project = 'minor-website' AND slug = 'minor-web'
  AND routing_mode = 'legacy' AND routing_target = '';

-- All IDs, credentials, owners, approval/revocation, descriptions and readiness
-- are preserved. Explicitly configured targets and other projects are untouched.
