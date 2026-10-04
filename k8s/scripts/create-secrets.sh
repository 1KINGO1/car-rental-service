#!/usr/bin/env bash
# Create DB Secrets in Kubernetes (for Helm secrets.create=false).
# Usage (from repo root):
#   ./k8s/scripts/create-secrets.sh --generate
#   CUSTOMER_DB_PASSWORD=... ./k8s/scripts/create-secrets.sh
#   ./k8s/scripts/create-secrets.sh --generate --write-values
set -euo pipefail

CHART_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NAMESPACE="${NAMESPACE:-car-rental}"
GENERATE=0
WRITE_VALUES=0
VALUES_FILE="${VALUES_FILE:-$CHART_DIR/values.secrets.yaml}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --generate|-Generate) GENERATE=1; shift ;;
    --write-values) WRITE_VALUES=1; shift ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --values-file) VALUES_FILE="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,7p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$GENERATE" -eq 1 ]]; then
  CUSTOMER_DB_PASSWORD="$(openssl rand -base64 24)"
  FLEET_DB_PASSWORD="$(openssl rand -base64 24)"
  BOOKING_DB_PASSWORD="$(openssl rand -base64 24)"
  PAYMENT_DB_PASSWORD="$(openssl rand -base64 24)"
  echo "Generated random DB passwords (not printed)."
else
  : "${CUSTOMER_DB_PASSWORD:?Set CUSTOMER_DB_PASSWORD or use --generate}"
  : "${FLEET_DB_PASSWORD:?Set FLEET_DB_PASSWORD or use --generate}"
  : "${BOOKING_DB_PASSWORD:?Set BOOKING_DB_PASSWORD or use --generate}"
  : "${PAYMENT_DB_PASSWORD:?Set PAYMENT_DB_PASSWORD or use --generate}"
fi

command -v kubectl >/dev/null || { echo "kubectl is not installed or not on PATH" >&2; exit 1; }

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

apply_secret() {
  local name="$1"
  local password="$2"
  kubectl create secret generic "$name" \
    --namespace "$NAMESPACE" \
    --from-literal=POSTGRES_PASSWORD="$password" \
    --from-literal=DB_PASSWORD="$password" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "Applied Secret/$name"
}

apply_secret customer-db-secret "$CUSTOMER_DB_PASSWORD"
apply_secret fleet-db-secret "$FLEET_DB_PASSWORD"
apply_secret booking-db-secret "$BOOKING_DB_PASSWORD"
apply_secret payment-db-secret "$PAYMENT_DB_PASSWORD"

if [[ "$WRITE_VALUES" -eq 1 ]]; then
  cat > "$VALUES_FILE" <<EOF
secrets:
  create: true
  passwords:
    customer: "$CUSTOMER_DB_PASSWORD"
    fleet: "$FLEET_DB_PASSWORD"
    booking: "$BOOKING_DB_PASSWORD"
    payment: "$PAYMENT_DB_PASSWORD"
EOF
  echo "Wrote $VALUES_FILE (gitignored; keep private)."
fi

echo "Secrets ready in namespace '$NAMESPACE'."
echo "Next: ./k8s/scripts/deploy.sh"
