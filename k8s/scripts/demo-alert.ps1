param(
    [string]$Namespace = "car-rental-lab06",
    [ValidateSet("customer-service", "fleet-service", "booking-service", "payment-service")]
    [string]$Service = "customer-service",
    [string]$OutputDir = "",
    [int]$PrometheusPort = 19090,
    [int]$AlertmanagerPort = 19093,
    [int]$ReceiverPort = 19080,
    [ValidateRange(0, 60)]
    [int]$HoldFiringSeconds = 0
)
$ErrorActionPreference = "Stop"
if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot ("../../docs/evidence/alert-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
$null = New-Item -ItemType Directory -Path $OutputDir -Force
$OutputDir = (Resolve-Path $OutputDir).Path
$forwards = @()
$changed = $false
$firingDelivered = $false
$primaryError = $null
$podName = $null
$originalPort = $null
$prom = "http://127.0.0.1:$PrometheusPort"
$am = "http://127.0.0.1:$AlertmanagerPort"
$receiver = "http://127.0.0.1:$ReceiverPort"
function Save-Json([string]$Name, $Value) {
    $Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir $Name) -Encoding utf8
}
function Get-Json([string]$Url) { Invoke-RestMethod -Uri $Url -TimeoutSec 5 }
function Wait-For([string]$Description, [scriptblock]$Check, [int]$Seconds = 150) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try { $value = & $Check; if ($value) { return $value } } catch { Write-Verbose $_ }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description. Evidence and forwarding logs: $OutputDir"
}
function Has-Alert($Response, [string]$State) {
    @($Response.data.alerts | Where-Object {
        $_.labels.alertname -eq "MicroserviceDown" -and $_.labels.pod -eq $podName -and $_.state -eq $State
    }).Count -gt 0
}
function Has-Notification($Events, [string]$State) {
    @($Events | Where-Object {
        $_.payload.status -eq $State -and
        @($_.payload.alerts | Where-Object { $_.labels.alertname -eq "MicroserviceDown" -and $_.labels.pod -eq $podName }).Count -gt 0 -and
        ([DateTimeOffset]$_.receivedAt).ToUniversalTime() -ge $startedAt
    }).Count -gt 0
}
try {
    foreach ($entry in @(
        @{name = "prometheus"; local = $PrometheusPort; remote = 9090},
        @{name = "alertmanager"; local = $AlertmanagerPort; remote = 9093},
        @{name = "alert-webhook"; local = $ReceiverPort; remote = 8080}
    )) {
        $arguments = @("-n", $Namespace, "port-forward", "svc/$($entry.name)", "$($entry.local):$($entry.remote)", "--address=127.0.0.1")
        $forwards += Start-Process -FilePath "kubectl" -ArgumentList $arguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $OutputDir "$($entry.name)-forward.log") `
            -RedirectStandardError (Join-Path $OutputDir "$($entry.name)-forward-error.log")
    }
    $null = Wait-For "Prometheus API" { (Get-Json "$prom/api/v1/status/buildinfo").status -eq "success" } 30
    $null = Wait-For "Alertmanager API" { Get-Json "$am/api/v2/status" } 30
    $null = Wait-For "notification receiver" { (Get-Json "$receiver/health").status -eq "UP" } 30
    $baseline = Wait-For "all four application targets UP" {
        $targets = Get-Json "$prom/api/v1/targets"
        $apps = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq "spring-boot" })
        if (@($apps.labels.app | Sort-Object -Unique).Count -eq 4 -and @($apps | Where-Object { $_.health -ne "up" }).Count -eq 0) { $targets }
    }
    Save-Json "01-targets-up.json" $baseline
    Save-Json "02-rules-before.json" (Get-Json "$prom/api/v1/rules")
    kubectl get pods,deployments,services -n $Namespace -o wide | Set-Content -LiteralPath (Join-Path $OutputDir "00-kubernetes.txt")
    $pods = kubectl get pods -n $Namespace -l "app=$Service" -o json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "Cannot select application pod." }
    $pod = @($pods.items | Where-Object { $_.status.phase -eq "Running" })[0]
    if (-not $pod) { throw "No running pod for $Service." }
    $podName = $pod.metadata.name
    $originalPort = $pod.metadata.annotations.'prometheus.io/port'
    $startedAt = [DateTimeOffset]::UtcNow
    Save-Json "03-experiment.json" @{
        startedAt = $startedAt.ToString("o"); namespace = $Namespace; pod = $podName
        originalPort = $originalPort; failurePort = "65535"
        simulation = "Scrape endpoint outage: the live pod remains discovered, but Prometheus cannot connect to the annotated port."
    }
    # Change only this running pod's scrape annotation, not its Deployment or real HTTP port.
    # The target remains in discovery and the next real scrape records up=0.
    kubectl annotate pod $podName -n $Namespace prometheus.io/port=65535 --overwrite
    if ($LASTEXITCODE -ne 0) { throw "Cannot inject scrape outage." }
    $changed = $true
    $pending = Wait-For "MicroserviceDown Pending" {
        $alerts = Get-Json "$prom/api/v1/alerts"; if (Has-Alert $alerts "pending") { $alerts }
    } 45
    Save-Json "04-alert-pending.json" $pending
    $firing = Wait-For "MicroserviceDown Firing" {
        $alerts = Get-Json "$prom/api/v1/alerts"; if (Has-Alert $alerts "firing") { $alerts }
    } 100
    Save-Json "05-alert-firing.json" $firing
    Save-Json "06-targets-down.json" (Get-Json "$prom/api/v1/targets")
    $notification = Wait-For "actual Alertmanager firing webhook delivery" {
        $events = Get-Json "$receiver/events"; if (Has-Notification $events "firing") { ,$events }
    }
    Save-Json "07-webhook-firing.json" $notification
    $firingDelivered = $true
    Save-Json "08-alertmanager-firing.json" (Get-Json "$am/api/v2/alerts")
    if ($HoldFiringSeconds -gt 0) { Start-Sleep -Seconds $HoldFiringSeconds }
} catch {
    $primaryError = $_
    throw
} finally {
    if ($changed) {
        if ($originalPort) {
            kubectl annotate pod $podName -n $Namespace "prometheus.io/port=$originalPort" --overwrite
        } else {
            kubectl annotate pod $podName -n $Namespace prometheus.io/port-
        }
        if ($LASTEXITCODE -ne 0) { Write-Warning "Restore failed or pod was replaced. Check $podName and its scrape annotation in $Namespace." }
    }
    # Keep forwarding until recovery evidence is captured; also execute cleanup if any check fails.
    try {
        if ($changed) {
            $restored = Wait-For "all application targets recovered" {
                $targets = Get-Json "$prom/api/v1/targets"
                $apps = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq "spring-boot" })
                if (@($apps.labels.app | Sort-Object -Unique).Count -eq 4 -and @($apps | Where-Object { $_.health -ne "up" }).Count -eq 0) { $targets }
            }
            Save-Json "09-targets-recovered.json" $restored
            if ($firingDelivered) {
                $resolved = Wait-For "actual Alertmanager resolved webhook delivery" {
                    $events = Get-Json "$receiver/events"; if (Has-Notification $events "resolved") { ,$events }
                }
                Save-Json "10-webhook-resolved.json" $resolved
            }
            $inactive = Wait-For "MicroserviceDown no longer active in Prometheus" {
                $alerts = Get-Json "$prom/api/v1/alerts"
                if (-not (Has-Alert $alerts "firing") -and -not (Has-Alert $alerts "pending")) { $alerts }
            }
            Save-Json "11-alerts-recovered.json" $inactive
            kubectl logs deployment/alert-webhook -n $Namespace | Set-Content -LiteralPath (Join-Path $OutputDir "12-webhook.log")
        }
    } catch {
        if ($primaryError) { Write-Warning "Recovery capture also failed: $_. Original experiment failure is preserved." }
        else { throw }
    } finally {
        foreach ($process in $forwards) {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
        }
    }
}
Write-Host "Verified Pending -> Firing -> Resolved and webhook delivery. Evidence: $OutputDir"
