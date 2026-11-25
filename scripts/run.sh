#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR/frontend"
if [ ! -d node_modules ]; then npm ci; fi
npm run build
mkdir -p "$PROJECT_DIR/backend/src/main/resources/static"
cp -R dist/. "$PROJECT_DIR/backend/src/main/resources/static/"
cd "$PROJECT_DIR/backend"
exec mvn spring-boot:run
