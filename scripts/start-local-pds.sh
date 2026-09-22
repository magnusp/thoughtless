#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Starting local ATProto PDS container..."
docker compose -f "$PROJECT_ROOT/docker/docker-compose.pds.yml" up -d

echo "Local PDS is starting up on http://localhost:3000"
echo "Admin password: adminpassword123"
echo "To check logs: docker compose -f $PROJECT_ROOT/docker/docker-compose.pds.yml logs -f"
