param([string]$Namespace='car-rental-lab07')
$ErrorActionPreference='Stop'
function Check { if ($LASTEXITCODE -ne 0) { throw 'Previous command failed' } }
kubectl get nodes; Check
foreach ($svc in @('customer','fleet','booking','payment')) {
  docker build --build-arg "SERVICE=$svc-service" -t "car-rental/$svc-service`:lab07" .; Check
}
$existing = 0
foreach ($name in @('customer','fleet','booking','payment')) {
  kubectl -n $Namespace get secret "$name-db-secret" --ignore-not-found -o name 2>$null | ForEach-Object { if ($_ ) { $existing++ } }
}
if ($existing -eq 0) { & ./k8s/scripts/create-secrets.ps1 -Namespace $Namespace -Generate }
elseif ($existing -ne 4) { throw 'Partial DB Secrets: resolve missing secrets before deployment; existing passwords were preserved.' }
helm upgrade --install car-rental ./k8s -n $Namespace --create-namespace -f ./k8s/values.tracing.yaml --wait --timeout 10m; Check
Write-Host 'Run python scripts/demo_tracing.py; then kubectl port-forward -n car-rental-lab07 svc/grafana 3000:3000'
