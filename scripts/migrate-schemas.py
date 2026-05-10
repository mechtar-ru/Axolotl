#!/usr/bin/env python3
"""
Migration script: re-save all WorkflowSchema nodes to sync node properties with JSON blob.
Run this after deploying fix-03 to ensure all existing data is consistent.

Usage: python3 scripts/migrate-schemas.py [--uri=bolt://localhost:7687] [--user=neo4j] [--password=password]
"""

import argparse
import json
from neo4j import GraphDatabase


def main():
    parser = argparse.ArgumentParser(description="Re-save all WorkflowSchema nodes to use JSON blob as single source of truth")
    parser.add_argument("--uri", default="bolt://localhost:7687", help="Neo4j URI")
    parser.add_argument("--user", default="neo4j", help="Neo4j username")
    parser.add_argument("--password", default="password", help="Neo4j password")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without writing")
    args = parser.parse_args()

    driver = GraphDatabase.driver(args.uri, auth=(args.user, args.password))
    migrated = 0
    errors = 0

    with driver.session() as session:
        result = session.run("""
            MATCH (s:WorkflowSchema)
            RETURN s.id as id, s.name as name, s.userId as userId,
                   s.workspaceId as workspaceId, s.createdAt as createdAt,
                   s.updatedAt as updatedAt, s.data as data
        """)

        for record in result:
            try:
                schema_id = record["id"]
                data = record.get("data")
                
                if not data:
                    print(f"WARN: Schema {schema_id} has no data blob, skipping")
                    continue
                
                parsed = json.loads(data)
                
                if args.dry_run:
                    has_diff = False
                    for field in ["name", "userId", "workspaceId", "createdAt", "updatedAt"]:
                        blob_val = parsed.get(field)
                        node_val = record.get(field)
                        if node_val is not None and blob_val != node_val:
                            print(f"  {schema_id}.{field}: node='{node_val}' vs blob='{blob_val}'")
                            has_diff = True
                    
                    if has_diff:
                        print(f"Would migrate: {schema_id}")
                        migrated += 1
                    else:
                        print(f"OK: {schema_id} (consistent)")
                else:
                    # Re-save using only the data blob (removes stale node properties)
                    session.run(
                        """
                        MERGE (s:WorkflowSchema {id: $id})
                        SET s.data = $data
                        """,
                        id=schema_id,
                        data=json.dumps(parsed, ensure_ascii=False)
                    )
                    print(f"Migrated: {schema_id}")
                    migrated += 1

            except Exception as e:
                print(f"ERROR: {e}")
                errors += 1

    driver.close()
    print(f"\nMigration complete: {migrated} migrated, {errors} errors")


if __name__ == "__main__":
    main()
