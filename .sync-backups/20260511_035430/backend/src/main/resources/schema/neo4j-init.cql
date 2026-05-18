-- Fulltext index for AST body search
CREATE FULLTEXT INDEX IF NOT EXISTS method_body_fts IF NOT EXISTS
FOR (m:Method) ON EACH [m.body, m.description];

CREATE FULLTEXT INDEX IF NOT EXISTS class_body_fts IF NOT EXISTS
FOR (c:Class) ON EACH [c.astBody, c.description, c.imports];