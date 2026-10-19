param(
    [string]$Namespace = "car-rental-lab06",
    [string]$Release = "car-rental-lab06",
    [string]$Helm = "helm",
    [switch]$BuildImages
)
$ErrorActionPreference = "Stop"
$ChartDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
function Assert-Native([string]$Message) { if ($LASTEXITCODE -ne 0) { throw $Message } }
kubectl cluster-info
Assert-Native "The selected Kubernetes cluster is unavailable."
if ($BuildImages) { & (Join-Path $PSScriptRoot "build-images.ps1") -Tag lab06 }
kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
Assert-Native "Cannot create namespace."
foreach ($name in @("customer", "fleet", "booking", "payment")) {
    $existing = kubectl get secret "$name-db-secret" -n $Namespace --ignore-not-found -o name
    Assert-Native "Cannot inspect database secret."
    if (-not $existing) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $password = [Convert]::ToBase64String($bytes)
        # Credentials stay in memory: never write them to reports, source files or terminal output.
        $secret = @{
            apiVersion = "v1"; kind = "Secret"
            metadata = @{name = "$name-db-secret"; namespace = $Namespace}
            type = "Opaque"
            stringData = @{DB_PASSWORD = $password; POSTGRES_PASSWORD = $password}
        }
        $secret | ConvertTo-Json -Depth 5 -Compress | kubectl create -f -
        Assert-Native "Cannot create database secret."
        $password = $null; $secret = $null
    }
}
& $Helm lint $ChartDir -f (Join-Path $ChartDir "values.monitoring.yaml")
Assert-Native "Helm lint failed."
& $Helm upgrade --install $Release $ChartDir --namespace $Namespace `
    -f (Join-Path $ChartDir "values.monitoring.yaml") --wait --timeout 10m
Assert-Native "Helm deployment failed. Inspect pods and events in the lab namespace."
kubectl get pods,services -n $Namespace -o wide
Write-Host "Ready. Run k8s/scripts/demo-alert.ps1 to capture a real Firing -> Resolved cycle."
