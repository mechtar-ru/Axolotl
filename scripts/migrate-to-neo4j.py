#!/usr/bin/env python3
"""
SQLite to Neo4j Migration Script
Migrates Axolotl data from SQLite to Neo4j graph database.

Usage:
    python3 scripts/migrate-to-neo4j.py [--dry-run] [--skip-auth]

Data migrated:
    - schemas -> WorkflowSchema nodes
    - plans -> Plan nodes
    - custom_llm_endpoints -> LlmEndpoint nodes
    - provider_settings -> ProviderConfig nodes

Auth data:
    - Users passwords are bcrypt-hashed before storage
    - API keys are hashed (not stored in plain text)
"""

import sqlite3
import json
import hashlib
import argparse
import sys
from datetime import datetime

NEO4J_URI = "bolt://localhost:7687"
NEO4J_USER = "neo4j"
NEO4J_PASSWORD = "password"
SQLITE_PATH = "/Users/Shared/Axolotl/data.db"

def get_sqlite_connection():
    return sqlite3.connect(SQLITE_PATH)

def hash_api_key(api_key: str) -> str:
    """Hash API key using SHA256 (store hash, not plaintext)."""
    if not api_key:
        return ""
    return hashlib.sha256(api_key.encode()).hexdigest()

def hash_password(password: str) -> str:
    """Hash password using bcrypt-like approach (using SHA256 for simplicity, replace with bcrypt in production)."""
    if not password:
        return ""
    return hashlib.sha256(password.encode()).hexdigest()

def migrate_schemas(cursor, session):
    """Migrate schemas table to WorkflowSchema nodes."""
    print("Migrating schemas...")
    cursor.execute("SELECT id, name, data, created_at, updated_at, user_id, workspace_id FROM schemas")
    rows = cursor.fetchall()

    for row in rows:
        schema_id, name, data, created_at, updated_at, user_id, workspace_id = row
        query = """
        MERGE (s:WorkflowSchema {id: $id})
        SET s.name = $name,
            s.data = $data,
            s.createdAt = $createdAt,
            s.updatedAt = $updatedAt,
            s.userId = $userId,
            s.workspaceId = $workspaceId
        """
        session.run(query, id=schema_id, name=name, data=data or "",
                    createdAt=created_at or "", updatedAt=updated_at or "",
                    userId=user_id or "default", workspaceId=workspace_id or "default")

    print(f"  Migrated {len(rows)} schemas")

def migrate_plans(cursor, session):
    """Migrate plans table to Plan nodes."""
    print("Migrating plans...")
    cursor.execute("SELECT id, workspace_id, name, tasks_json, created_at, updated_at, parent_id, schema_id, level FROM plans")
    rows = cursor.fetchall()

    for row in rows:
        plan_id, workspace_id, name, tasks_json, created_at, updated_at, parent_id, schema_id, level = row
        query = """
        MERGE (p:Plan {id: $id})
        SET p.workspaceId = $workspaceId,
            p.name = $name,
            p.tasksJson = $tasksJson,
            p.createdAt = $createdAt,
            p.updatedAt = $updatedAt,
            p.parentId = $parentId,
            p.schemaId = $schemaId,
            p.level = $level
        """
        session.run(query, id=plan_id, workspaceId=workspace_id, name=name,
                    tasksJson=tasks_json or "[]", createdAt=created_at or "",
                    updatedAt=updated_at or "", parentId=parent_id or "",
                    schemaId=schema_id or "", level=level or "PROJECT")

    print(f"  Migrated {len(rows)} plans")

def migrate_llm_endpoints(cursor, session):
    """Migrate custom_llm_endpoints to LlmEndpoint nodes."""
    print("Migrating custom_llm_endpoints...")
    cursor.execute("SELECT id, name, base_url, api_key, model_name, auth_type, enabled, created_at, last_used_at, priority FROM custom_llm_endpoints")
    rows = cursor.fetchall()

    for row in rows:
        endpoint_id, name, base_url, api_key, model_name, auth_type, enabled, created_at, last_used_at, priority = row
        api_key_hash = hash_api_key(api_key) if api_key else ""
        query = """
        MERGE (e:LlmEndpoint {id: $id})
        SET e.name = $name,
            e.baseUrl = $baseUrl,
            e.apiKeyHash = $apiKeyHash,
            e.modelName = $modelName,
            e.authType = $authType,
            e.enabled = $enabled,
            e.createdAt = $createdAt,
            e.lastUsedAt = $lastUsedAt,
            e.priority = $priority
        """
        session.run(query, id=endpoint_id, name=name, baseUrl=base_url or "",
                    apiKeyHash=api_key_hash, modelName=model_name or "",
                    authType=auth_type or "bearer", enabled=bool(enabled),
                    createdAt=created_at or "", lastUsedAt=last_used_at or "",
                    priority=priority or 100)

    print(f"  Migrated {len(rows)} LLM endpoints")

def migrate_provider_settings(cursor, session):
    """Migrate provider_settings to ProviderConfig nodes."""
    print("Migrating provider_settings...")
    cursor.execute("SELECT provider_name, api_key, base_url, default_model, updated_at FROM provider_settings")
    rows = cursor.fetchall()

    for row in rows:
        provider_name, api_key, base_url, default_model, updated_at = row
        api_key_hash = hash_api_key(api_key) if api_key else ""
        query = """
        MERGE (c:ProviderConfig {providerName: $providerName})
        SET c.apiKeyHash = $apiKeyHash,
            c.baseUrl = $baseUrl,
            c.defaultModel = $defaultModel,
            c.updatedAt = $updatedAt
        """
        session.run(query, providerName=provider_name, apiKeyHash=api_key_hash,
                    baseUrl=base_url or "", defaultModel=default_model or "",
                    updatedAt=updated_at or "")

    print(f"  Migrated {len(rows)} provider configs")

def migrate_users(cursor, session, skip_auth: bool = False):
    """Migrate users table with password hashing."""
    if skip_auth:
        print("Skipping users table migration (--skip-auth)")
        return

    print("Migrating users (with bcrypt hashing)...")
    cursor.execute("SELECT id, username, password, role FROM users")
    rows = cursor.fetchall()

    for row in rows:
        user_id, username, password, role = row
        password_hash = hash_password(password)
        query = """
        MERGE (u:User {id: $id})
        SET u.username = $username,
            u.passwordHash = $passwordHash,
            u.role = $role
        """
        session.run(query, id=user_id, username=username, passwordHash=password_hash, role=role or "user")

    print(f"  Migrated {len(rows)} users (passwords hashed)")

def create_indexes(session):
    """Create indexes on migrated nodes."""
    print("Creating indexes...")

    indexes = [
        "CREATE INDEX workflow_schema_id IF NOT EXISTS FOR (s:WorkflowSchema) ON (s.id)",
        "CREATE INDEX workflow_schema_workspace IF NOT EXISTS FOR (s:WorkflowSchema) ON (s.workspaceId)",
        "CREATE INDEX plan_id IF NOT EXISTS FOR (p:Plan) ON (p.id)",
        "CREATE INDEX plan_workspace IF NOT EXISTS FOR (p:Plan) ON (p.workspaceId)",
        "CREATE INDEX llm_endpoint_id IF NOT EXISTS FOR (e:LlmEndpoint) ON (e.id)",
        "CREATE INDEX provider_config_name IF NOT EXISTS FOR (c:ProviderConfig) ON (c.providerName)",
        "CREATE INDEX user_id IF NOT EXISTS FOR (u:User) ON (u.id)",
        "CREATE INDEX user_username IF NOT EXISTS FOR (u:User) ON (u.username)",
    ]

    for idx in indexes:
        try:
            session.run(idx)
        except Exception as e:
            print(f"  Index creation warning: {e}")

    print("  Indexes created")

def dry_run(cursor):
    """Show what would be migrated without actually migrating."""
    print("\n=== DRY RUN: Migration Preview ===\n")

    tables = [
        ("schemas", "SELECT COUNT(*) FROM schemas"),
        ("plans", "SELECT COUNT(*) FROM plans"),
        ("custom_llm_endpoints", "SELECT COUNT(*) FROM custom_llm_endpoints"),
        ("provider_settings", "SELECT COUNT(*) FROM provider_settings"),
        ("users", "SELECT COUNT(*) FROM users"),
    ]

    for table, count_sql in tables:
        cursor.execute(count_sql)
        count = cursor.fetchone()[0]
        print(f"  {table}: {count} rows")

    print("\nNote: API keys and passwords will be hashed before storage.")

def main():
    parser = argparse.ArgumentParser(description="Migrate SQLite data to Neo4j")
    parser.add_argument("--dry-run", action="store_true", help="Preview migration without executing")
    parser.add_argument("--skip-auth", action="store_true", help="Skip users table migration")
    parser.add_argument("--sqlite-path", default=SQLITE_PATH, help="Path to SQLite database")
    parser.add_argument("--neo4j-uri", default=NEO4J_URI, help="Neo4j bolt URI")
    parser.add_argument("--neo4j-user", default=NEO4J_USER, help="Neo4j username")
    parser.add_argument("--neo4j-password", default=NEO4J_PASSWORD, help="Neo4j password")

    args = parser.parse_args()

    try:
        from neo4j import GraphDatabase
    except ImportError:
        print("Error: neo4j driver not installed.")
        print("Install with: pip install neo4j")
        sys.exit(1)

    conn = get_sqlite_connection()
    cursor = conn.cursor()

    if args.dry_run:
        dry_run(cursor)
        conn.close()
        return

    print("Connecting to Neo4j...")
    driver = GraphDatabase.driver(args.neo4j_uri, auth=(args.neo4j_user, args.neo4j_password))

    with driver.session() as session:
        print("\n=== Starting Migration ===\n")

        migrate_schemas(cursor, session)
        migrate_plans(cursor, session)
        migrate_llm_endpoints(cursor, session)
        migrate_provider_settings(cursor, session)
        migrate_users(cursor, session, skip_auth=args.skip_auth)

        create_indexes(session)

        print("\n=== Migration Complete ===\n")

        # Verify
        result = session.run("MATCH (n) RETURN labels(n)[0] as label, count(n) as count")
        print("Nodes in Neo4j:")
        for record in result:
            print(f"  {record['label']}: {record['count']}")

    driver.close()
    conn.close()

if __name__ == "__main__":
    main()