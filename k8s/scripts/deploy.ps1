# Deploy Car Rental to the current kubectl context.
# Prerequisites: images built/loaded, secrets created.
$ErrorActionPreference = "Stop"
$K8S_DIR = Split-Path -Parent $PSScriptRoot

Write-Host "==> Namespace + ConfigMaps"
kubectl apply -f "$K8S_DIR/00-namespace.yaml"
kubectl apply -f "$K8S_DIR/01-configmaps.yaml"

Write-Host "==> Databases"
Get-ChildItem "$K8S_DIR\1*.yaml" | ForEach-Object {
    kubectl apply -f $_.FullName
}

Write-Host "==> Waiting for databases"
kubectl rollout status deployment/customer-db -n car-rental --timeout=180s
kubectl rollout status deployment/fleet-db -n car-rental --timeout=180s
kubectl rollout status deployment/booking-db -n car-rental --timeout=180s
kubectl rollout status deployment/payment-db -n car-rental --timeout=180s

Write-Host "==> Application services"
Get-ChildItem "$K8S_DIR\2*.yaml" | ForEach-Object {
    kubectl apply -f $_.FullName
}

Write-Host "==> Waiting for services"
kubectl rollout status deployment/customer-service -n car-rental --timeout=300s
kubectl rollout status deployment/fleet-service -n car-rental --timeout=300s
kubectl rollout status deployment/booking-service -n car-rental --timeout=300s
kubectl rollout status deployment/payment-service -n car-rental --timeout=300s

Write-Host "==> Status"
kubectl get pods,svc,deploy -n car-rental -o wide
