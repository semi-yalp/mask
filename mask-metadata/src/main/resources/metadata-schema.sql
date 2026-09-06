-- Metadata service schema (init via spring.sql.init.schema-locations).
-- Named explicitly so mask-core's classpath:schema.sql is not picked up.
-- Placeholder statement: a script with no executable statements fails
-- Spring SQL init; real table DDL lands with the storage task.
SELECT 1;
