# Creates Opaque Secrets in namespace car-rental without storing passwords in Git.
# Usage:
#   $env:CUSTOMER_DB_PASSWORD = "..."
#   $env:FLEET_DB_PASSWORD = "..."
#   $env:BOOKING_DB_PASSWORD = "..."
#   $env:PAYMENT_DB_PASSWORD = "..."
#   .\k8s\scripts\create-secrets.ps1
#
# Or pass -Generate to create random passwords for local/demo clusters.

param(
    [switch]$Generate,
    [string]$Namespace = "car-rental"
)

$ErrorActionPreference = "Stop"

function Require-Password([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Missing password: set env var $Name or use -Generate"
    }
}

if ($Generate) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    function New-Password {
        $bytes = New-Object byte[] 24
        $rng.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    }
    $CustomerPassword = New-Password
    $FleetPassword = New-Password
    $BookingPassword = New-Password
    $PaymentPassword = New-Password
    Write-Host "Generated random DB passwords for local apply (not printed)."
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

kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -

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
    Write-Host "Applied Secret/$($pair.Name)"
}

Write-Host "Secrets applied to namespace '$Namespace'."
