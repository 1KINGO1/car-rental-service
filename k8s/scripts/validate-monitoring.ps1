param([string]$Helm = "helm")
$ErrorActionPreference = "Stop"
$chart = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
& $Helm lint $chart -f (Join-Path $chart "values.monitoring.yaml")
if ($LASTEXITCODE -ne 0) { throw "Helm lint failed" }
$rendered = & $Helm template car-rental-lab06 $chart -n car-rental-lab06 `
    -f (Join-Path $chart "values.monitoring.yaml") --show-only templates/monitoring.yaml
if ($LASTEXITCODE -ne 0) { throw "Helm template failed" }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("car-rental-promcheck-" + [Guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $temporary
$configLines = @()
$capture = $false
foreach ($line in $rendered) {
    if ($line -eq "  alert-rules.yml: |") { break }
    if ($capture) { $configLines += $line.Substring(4) }
    if ($line -eq "  prometheus.yml: |") { $capture = $true }
}
if (-not $configLines.Count) { throw "Cannot extract rendered Prometheus configuration" }
try {
    $configLines | Set-Content -LiteralPath (Join-Path $temporary "prometheus.yml") -Encoding utf8
    docker run --rm --entrypoint /bin/promtool --mount "type=bind,source=$temporary,target=/config,readonly" `
        prom/prometheus:v3.5.0 check config --syntax-only /config/prometheus.yml
    if ($LASTEXITCODE -ne 0) { throw "Prometheus scrape/discovery configuration failed" }
} finally {
    Remove-Item -LiteralPath (Join-Path $temporary "prometheus.yml") -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temporary -ErrorAction SilentlyContinue
}
$config = Join-Path $chart "files/monitoring"
docker run --rm --entrypoint /bin/promtool --mount "type=bind,source=$config,target=/config,readonly" `
    prom/prometheus:v3.5.0 check rules /config/alert-rules.yml
if ($LASTEXITCODE -ne 0) { throw "Prometheus alert-rule syntax failed" }
docker run --rm --entrypoint /bin/promtool --mount "type=bind,source=$chart,target=/chart,readonly" `
    -w /chart/tests prom/prometheus:v3.5.0 test rules alert-rules.test.yml
if ($LASTEXITCODE -ne 0) { throw "Prometheus rule unit tests failed" }
docker run --rm --entrypoint /bin/amtool --mount "type=bind,source=$config,target=/config,readonly" `
    prom/alertmanager:v0.28.1 check-config /config/alertmanager.yml
if ($LASTEXITCODE -ne 0) { throw "Alertmanager configuration failed" }
