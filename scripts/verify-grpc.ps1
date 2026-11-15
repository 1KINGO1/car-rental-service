param([switch]$AllTests)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $maven = if (Test-Path './mvnw.cmd') { './mvnw.cmd' } else { 'mvn.cmd' }
    New-Item -ItemType Directory -Force 'docs/evidence/grpc' | Out-Null
    $arguments = @('-B', '-Pgrpc-demo', 'verify')
    if (-not $AllTests) {
        $arguments += @('-pl', 'fleet-service,booking-service', '-am',
            '-Dtest=FleetGrpcIntegrationTest,GrpcBookingIntegrationTest', '-Dsurefire.failIfNoSpecifiedTests=false')
    }
    & $maven @arguments 2>&1 | Tee-Object 'docs/evidence/grpc/verification.log'
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed: $LASTEXITCODE" }
} finally { Pop-Location }
