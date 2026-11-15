param([switch]$SkipBuild, [int]$FleetPort = 18082, [int]$BookingPort = 18083, [int]$GrpcPort = 19090)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $project
$fleetProcess = $null
$bookingProcess = $null
try {
    if (-not $SkipBuild) {
        & './mvnw.cmd' -B -Pgrpc-demo -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    $logs = Join-Path $project 'docs/evidence/grpc/demo'
    New-Item -ItemType Directory -Force $logs | Out-Null
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    # Only these two child processes are stopped in finally.
    $fleetProcess = Start-Process -FilePath $java -WorkingDirectory $project -WindowStyle Hidden -PassThru `
        -ArgumentList @('-jar', 'fleet-service/target/app.jar', "--server.port=$FleetPort", "--spring.grpc.server.port=$GrpcPort",
            '--DB_URL=jdbc:h2:mem:demo_fleet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1', '--DB_USER=sa', '--DB_PASSWORD=',
            '--logging.structured.format.console=') `
        -RedirectStandardOutput (Join-Path $logs 'fleet.log') -RedirectStandardError (Join-Path $logs 'fleet-error.log')
    $bookingProcess = Start-Process -FilePath $java -WorkingDirectory $project -WindowStyle Hidden -PassThru `
        -ArgumentList @('-jar', 'booking-service/target/app.jar', "--server.port=$BookingPort", '--spring.profiles.active=grpc',
            "--fleet.grpc.target=127.0.0.1:$GrpcPort", '--DB_URL=jdbc:h2:mem:demo_booking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
            '--DB_USER=sa', '--DB_PASSWORD=', '--messaging.enabled=false', '--spring.kafka.admin.auto-create=false',
            '--logging.structured.format.console=') `
        -RedirectStandardOutput (Join-Path $logs 'booking.log') -RedirectStandardError (Join-Path $logs 'booking-error.log')
    function Wait-Ready([int]$Port, $Process) {
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while ([DateTime]::UtcNow -lt $deadline) {
            $Process.Refresh()
            if ($Process.HasExited) { throw "Service on port $Port exited; see $logs" }
            try {
                $null = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 2
                return
            } catch { Start-Sleep -Milliseconds 300 }
        }
        throw "Service on port $Port was not ready in 60 seconds"
    }
    Wait-Ready $FleetPort $fleetProcess
    Wait-Ready $BookingPort $bookingProcess
    $cars = @()
    foreach ($spec in @(@{brand='Toyota';model='Corolla';plate='GRPC100';dailyRate=1200},
                         @{brand='Skoda';model='Octavia';plate='GRPC200';dailyRate=1500})) {
        $cars += Invoke-RestMethod "http://127.0.0.1:$FleetPort/api/vehicles" -Method Post -ContentType 'application/json' -Body ($spec | ConvertTo-Json)
    }
    $base = "http://127.0.0.1:$BookingPort/api/grpc"
    $items = @(@{requestId='a';vehicleId=$cars[0].id;days=2}, @{requestId='b';vehicleId=$cars[1].id;days=3}) | ConvertTo-Json
    $result = [ordered]@{
        unary = Invoke-RestMethod "$base/vehicles/$($cars[0].id)"
        serverStreaming = Invoke-RestMethod "$base/vehicles"
        clientStreaming = Invoke-RestMethod "$base/quotes/client-stream" -Method Post -ContentType 'application/json' -Body $items
        bidirectionalStreaming = Invoke-RestMethod "$base/quotes/bidi" -Method Post -ContentType 'application/json' -Body $items
    }
    $result | ConvertTo-Json -Depth 10 | Tee-Object (Join-Path $logs 'results.json')
    if ($result.unary.plate -ne 'GRPC100' -or $result.serverStreaming.Count -ne 2 -or
        $result.clientStreaming.total -ne '6900.00' -or $result.bidirectionalStreaming.Count -ne 2 -or
        $result.bidirectionalStreaming[0].amount -ne '2400.00' -or $result.bidirectionalStreaming[1].amount -ne '4500.00') {
        throw 'Demo returned unexpected results'
    }
    Write-Host 'PASS: all four gRPC call types between two separate Spring Boot processes.'
} finally {
    foreach ($process in @($bookingProcess, $fleetProcess)) {
        if ($null -ne $process) {
            $process.Refresh()
            if (-not $process.HasExited) { Stop-Process -Id $process.Id }
        }
    }
    Pop-Location
}
