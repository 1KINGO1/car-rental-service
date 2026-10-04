# Create DB Secrets in Kubernetes (for Helm secrets.create=false).
# Usage (from repo root):
#   .\k8s\scripts\create-secrets.ps1 -Generate
#   $env:CUSTOMER_DB_PASSWORD="..."; ... ; .\k8s\scripts\create-secrets.ps1
#   .\k8s\scripts\create-secrets.ps1 -Generate -WriteValuesFile
param(
    [switch]$Generate,
    [string]$Namespace = "car-rental",
    [switch]$WriteValuesFile,
    [string]$ValuesFile = ""
)

$ErrorActionPreference = "Stop"
$ChartDir = Resolve-Path (Join-Path $PSScriptRoot "..")

if (-not $ValuesFile) {
    $ValuesFile = Join-Path $ChartDir "values.secrets.yaml"
}

function Require-Password([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Missing password: set env var $Name or use -Generate"
    }
}

function New-Password {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

if ($Generate) {
    $CustomerPassword = New-Password
    $FleetPassword = New-Password
    $BookingPassword = New-Password
    $PaymentPassword = New-Password
    Write-Host "Generated random DB passwords (not printed)."
} else {
    $CustomerPassword = $env:CUSTOMER_DB_PASSWORD
    $FleetPassword = $env:FLEET_DB_PASSWORD
    $BookingPassword = $env:BOOKING_DB_PASSWORD
    $PaymentPassword = $env:PAYMENT_DB_PASSWORD
    Require-Password "CUSTOMER_DB_PASSWORD" $CustomerPassword
    Require-Password "FLEET_DB_PASSWORD" $FleetPassword
    Require-Password "BOOKING_DB_PASSWORD" $BookingPassword
    Require-Password "PAYMENT_DB_PASSWORD" $PaymentPassword
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "kubectl is not installed or not on PATH"
}

kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "Failed to ensure namespace '$Namespace'" }

$pairs = @(
    @{ Name = "customer-db-secret"; Password = $CustomerPassword },
    @{ Name = "fleet-db-secret"; Password = $FleetPassword },
    @{ Name = "booking-db-secret"; Password = $BookingPassword },
    @{ Name = "payment-db-secret"; Password = $PaymentPassword }
)

foreach ($pair in $pairs) {
    kubectl create secret generic $pair.Name `
        --namespace $Namespace `
        --from-literal=POSTGRES_PASSWORD=$pair.Password `
        --from-literal=DB_PASSWORD=$pair.Password `
        --dry-run=client -o yaml | kubectl apply -f -
    if ($LASTEXITCODE -ne 0) { throw "Failed to apply Secret/$($pair.Name)" }
    Write-Host "Applied Secret/$($pair.Name)"
}

if ($WriteValuesFile) {
    @"
secrets:
  create: true
  passwords:
    customer: "$CustomerPassword"
    fleet: "$FleetPassword"
    booking: "$BookingPassword"
    payment: "$PaymentPassword"
"@ | Set-Content -Path $ValuesFile -Encoding utf8
    Write-Host "Wrote $ValuesFile (gitignored; keep private)."
}

Write-Host "Secrets ready in namespace '$Namespace'."
Write-Host "Next: .\k8s\scripts\deploy.ps1"
