#!/usr/bin/env bash
# Helm lint + template dry-run (client). Server dry-run if cluster is ready.
set -euo pipefail

CHART_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NAMESPACE="${NAMESPACE:-car-rental}"
RELEASE="${RELEASE:-car-rental}"
EXAMPLE_SECRETS="$CHART_DIR/values.secrets.example.yaml"

if [[ ! -f "$EXAMPLE_SECRETS" ]]; then
  echo "Missing $EXAMPLE_SECRETS" >&2
  exit 1
fi

echo "==> helm lint"
helm lint "$CHART_DIR" -f "$EXAMPLE_SECRETS"

echo "==> helm template (client render)"
helm template "$RELEASE" "$CHART_DIR" -n "$NAMESPACE" -f "$EXAMPLE_SECRETS" >/dev/null
echo "OK: helm lint + template passed"

if ! kubectl get --raw=/readyz --request-timeout=3s >/dev/null 2>&1; then
  echo "WARN: cluster unavailable - server dry-run skipped"
  exit 0
fi

echo "==> helm template | kubectl apply --dry-run=server"
helm template "$RELEASE" "$CHART_DIR" -n "$NAMESPACE" -f "$EXAMPLE_SECRETS" |
  kubectl apply --dry-run=server -f -
echo "OK: client + server dry-run passed"
