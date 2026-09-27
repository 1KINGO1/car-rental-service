# Client-side and (if cluster available) server-side dry-run validation.
$ErrorActionPreference = "Stop"
$K8S_DIR = Split-Path -Parent $PSScriptRoot

function Assert-KubectlOk([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "FAILED: $Step (exit $LASTEXITCODE)"
    }
}

Write-Host "==> Client dry-run"
kubectl apply --dry-run=client -f "$K8S_DIR/00-namespace.yaml"
Assert-KubectlOk "client namespace"
kubectl apply --dry-run=client -f "$K8S_DIR/01-configmaps.yaml"
Assert-KubectlOk "client configmaps"
kubectl apply --dry-run=client -f "$K8S_DIR/02-secrets.example.yaml"
Assert-KubectlOk "client secrets"

Get-ChildItem "$K8S_DIR\1*.yaml", "$K8S_DIR\2*.yaml" | ForEach-Object {
    Write-Host "  validating $($_.Name)"
    kubectl apply --dry-run=client -f $_.FullName
    Assert-KubectlOk "client $($_.Name)"
}
Write-Host "OK: client dry-run passed"

$clusterOk = $false
try {
    kubectl get --raw=/readyz --request-timeout=3s 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $clusterOk = $true }
} catch {
    $clusterOk = $false
}

if (-not $clusterOk) {
    Write-Host "WARN: cluster unavailable - server dry-run skipped"
    exit 0
}

Write-Host "==> Ensure namespace exists (required for server dry-run)"
kubectl apply -f "$K8S_DIR/00-namespace.yaml"
Assert-KubectlOk "apply namespace"

Write-Host "==> Server dry-run"
kubectl apply --dry-run=server -f "$K8S_DIR/01-configmaps.yaml"
Assert-KubectlOk "server configmaps"
kubectl apply --dry-run=server -f "$K8S_DIR/02-secrets.example.yaml"
Assert-KubectlOk "server secrets"

Get-ChildItem "$K8S_DIR\1*.yaml", "$K8S_DIR\2*.yaml" | ForEach-Object {
    Write-Host "  validating $($_.Name)"
    kubectl apply --dry-run=server -f $_.FullName
    Assert-KubectlOk "server $($_.Name)"
}

Write-Host "OK: client + server dry-run passed"
