# Helm lint + template dry-run (client). Server dry-run if cluster is ready.
$ErrorActionPreference = "Stop"
$ChartDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$Namespace = "car-rental"
$Release = "car-rental"
$ExampleSecrets = Join-Path $ChartDir "values.secrets.example.yaml"

function Assert-Ok([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "FAILED: $Step (exit $LASTEXITCODE)" }
}

if (-not (Test-Path $ExampleSecrets)) {
    throw "Missing $ExampleSecrets"
}

Write-Host "==> helm lint"
helm lint $ChartDir -f $ExampleSecrets
Assert-Ok "helm lint"

Write-Host "==> helm template (client render)"
helm template $Release $ChartDir -n $Namespace -f $ExampleSecrets | Out-Null
Assert-Ok "helm template"
Write-Host "OK: helm lint + template passed"

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

Write-Host "==> helm template | kubectl apply --dry-run=server"
helm template $Release $ChartDir -n $Namespace -f $ExampleSecrets |
    kubectl apply --dry-run=server -f -
Assert-Ok "server dry-run"
Write-Host "OK: client + server dry-run passed"
