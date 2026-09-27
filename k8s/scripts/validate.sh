#!/usr/bin/env bash
# Client-side and (if cluster available) server-side dry-run validation.
set -euo pipefail

K8S_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Client dry-run"
kubectl apply --dry-run=client -f "$K8S_DIR/00-namespace.yaml"
kubectl apply --dry-run=client -f "$K8S_DIR/01-configmaps.yaml"
kubectl apply --dry-run=client -f "$K8S_DIR/02-secrets.example.yaml"
for f in "$K8S_DIR"/1*.yaml "$K8S_DIR"/2*.yaml; do
  echo "  validating $(basename "$f")"
  kubectl apply --dry-run=client -f "$f"
done
echo "OK: client dry-run passed"

if ! kubectl get --raw=/readyz --request-timeout=3s >/dev/null 2>&1; then
  echo "WARN: cluster unavailable - server dry-run skipped"
  exit 0
fi

echo "==> Ensure namespace exists (required for server dry-run)"
kubectl apply -f "$K8S_DIR/00-namespace.yaml"

echo "==> Server dry-run"
kubectl apply --dry-run=server -f "$K8S_DIR/01-configmaps.yaml"
kubectl apply --dry-run=server -f "$K8S_DIR/02-secrets.example.yaml"
for f in "$K8S_DIR"/1*.yaml "$K8S_DIR"/2*.yaml; do
  echo "  validating $(basename "$f")"
  kubectl apply --dry-run=server -f "$f"
done

echo "OK: client + server dry-run passed"
