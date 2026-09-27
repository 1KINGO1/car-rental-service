#!/usr/bin/env bash
# Creates Opaque Secrets in namespace car-rental without storing passwords in Git.
# Usage:
#   export CUSTOMER_DB_PASSWORD=...
#   export FLEET_DB_PASSWORD=...
#   export BOOKING_DB_PASSWORD=...
#   export PAYMENT_DB_PASSWORD=...
#   ./k8s/scripts/create-secrets.sh
#
# Or: ./k8s/scripts/create-secrets.sh --generate

set -euo pipefail

NAMESPACE="${NAMESPACE:-car-rental}"
GENERATE=0

if [[ "${1:-}" == "--generate" ]]; then
  GENERATE=1
fi

if [[ "$GENERATE" -eq 1 ]]; then
  CUSTOMER_DB_PASSWORD="$(openssl rand -base64 24)"
  FLEET_DB_PASSWORD="$(openssl rand -base64 24)"
  BOOKING_DB_PASSWORD="$(openssl rand -base64 24)"
  PAYMENT_DB_PASSWORD="$(openssl rand -base64 24)"
  echo "Generated random DB passwords for local apply (not printed)."
else
  : "${CUSTOMER_DB_PASSWORD:?Set CUSTOMER_DB_PASSWORD or use --generate}"
  : "${FLEET_DB_PASSWORD:?Set FLEET_DB_PASSWORD or use --generate}"
  : "${BOOKING_DB_PASSWORD:?Set BOOKING_DB_PASSWORD or use --generate}"
  : "${PAYMENT_DB_PASSWORD:?Set PAYMENT_DB_PASSWORD or use --generate}"
fi

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

echo "Secrets applied to namespace '$NAMESPACE'."
