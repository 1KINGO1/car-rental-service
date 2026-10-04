#!/usr/bin/env bash
# Build Docker images for car-rental microservices.
# Usage (from repo root):
#   ./k8s/scripts/build-images.sh
#   ./k8s/scripts/build-images.sh --tag 1.0.1
#   ./k8s/scripts/build-images.sh booking-service fleet-service
#   ./k8s/scripts/build-images.sh --registry myrepo --push
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

REGISTRY="${REGISTRY:-car-rental}"
TAG="${TAG:-1.0.0}"
PUSH=0
NO_CACHE=0
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --registry) REGISTRY="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --push) PUSH=1; shift ;;
    --no-cache) NO_CACHE=1; shift ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      SERVICES+=("$1")
      shift
      ;;
  esac
done

if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=(customer-service fleet-service booking-service payment-service)
fi

command -v docker >/dev/null || { echo "docker is not installed or not on PATH" >&2; exit 1; }

BUILD_ARGS=(build)
if [[ "$NO_CACHE" -eq 1 ]]; then
  BUILD_ARGS+=(--no-cache)
fi

for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${service}:${TAG}"
  echo "==> Building $image"
  docker "${BUILD_ARGS[@]}" \
    -t "$image" \
    --build-arg "SERVICE=${service}" \
    -f Dockerfile \
    .

  if [[ "$PUSH" -eq 1 ]]; then
    echo "==> Pushing $image"
    docker push "$image"
  fi
done

echo "==> Done"
docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}' | grep -E "^REPOSITORY|${REGISTRY}/" || true
