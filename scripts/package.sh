#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR/frontend"
npm ci
npm run build
mkdir -p "$PROJECT_DIR/backend/src/main/resources/static"
cp -R dist/. "$PROJECT_DIR/backend/src/main/resources/static/"
cd "$PROJECT_DIR/backend"
mvn -B clean verify
printf '\nBuilt backend/target/seatlock-1.0.0.jar (includes the web app).\n'
