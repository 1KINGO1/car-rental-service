#!/usr/bin/env bash
# Deploy Car Rental via Helm to the current kubectl context.
# Prerequisites: images built/loaded; secrets created (or -f values.secrets.yaml).
set -euo pipefail

RELEASE="${RELEASE:-car-rental}"
NAMESPACE="${NAMESPACE:-car-rental}"
CHART_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VALUES_FILE="${1:-}"

ARGS=(upgrade --install "$RELEASE" "$CHART_DIR" --namespace "$NAMESPACE" --create-namespace)
if [[ -n "$VALUES_FILE" ]]; then
  ARGS+=(-f "$VALUES_FILE")
fi

echo "==> helm ${ARGS[*]}"
helm "${ARGS[@]}"

echo "==> Status"
kubectl get pods,svc,deploy -n "$NAMESPACE" -o wide
