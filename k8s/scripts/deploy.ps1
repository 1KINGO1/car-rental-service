# Deploy Car Rental via Helm to the current kubectl context.
# Prerequisites: images built/loaded; secrets created (or -ValuesFile with secrets.create=true).
param(
    [string]$Release = "car-rental",
    [string]$Namespace = "car-rental",
    [string]$ValuesFile = "",
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$ChartDir = Resolve-Path (Join-Path $PSScriptRoot "..")

$helmArgs = @(
    "upgrade", "--install", $Release, $ChartDir,
    "--namespace", $Namespace,
    "--create-namespace"
)
if ($ValuesFile) {
    $helmArgs += @("-f", $ValuesFile)
}
if ($Wait) {
    $helmArgs += @("--wait", "--timeout", "10m")
}

Write-Host "==> helm $($helmArgs -join ' ')"
helm @helmArgs
if ($LASTEXITCODE -ne 0) { throw "helm upgrade failed (exit $LASTEXITCODE)" }

Write-Host "==> Status"
kubectl get pods,svc,deploy -n $Namespace -o wide
