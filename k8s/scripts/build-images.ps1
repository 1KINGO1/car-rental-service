# Build Docker images for car-rental microservices.
# Usage (from repo root):
#   .\k8s\scripts\build-images.ps1
#   .\k8s\scripts\build-images.ps1 -Tag 1.0.1
#   .\k8s\scripts\build-images.ps1 -Services booking-service,fleet-service
#   .\k8s\scripts\build-images.ps1 -Registry myrepo -Push
param(
    [string]$Registry = "car-rental",
    [string]$Tag = "1.0.0",
    [string[]]$Services = @(
        "customer-service",
        "fleet-service",
        "booking-service",
        "payment-service"
    ),
    [switch]$Push,
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $RepoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker is not installed or not on PATH"
}

$buildArgs = @("build")
if ($NoCache) { $buildArgs += "--no-cache" }

foreach ($service in $Services) {
    $image = "${Registry}/${service}:${Tag}"
    Write-Host "==> Building $image"
    docker @buildArgs `
        -t $image `
        --build-arg "SERVICE=$service" `
        -f Dockerfile `
        .
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build $image (exit $LASTEXITCODE)"
    }

    if ($Push) {
        Write-Host "==> Pushing $image"
        docker push $image
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to push $image (exit $LASTEXITCODE)"
        }
    }
}

Write-Host "==> Done"
docker images --format "table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}" |
    Select-String -Pattern "^REPOSITORY|$([regex]::Escape($Registry))/"
