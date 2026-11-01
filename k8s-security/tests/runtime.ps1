param(
  [string]$Namespace = 'car-rental-security',
  [Parameter(Mandatory=$true)][string]$IngressUrl,
  [string]$IngressHost = 'car-rental.local',
  [string]$Log = 'runtime-results.txt'
)
$ErrorActionPreference = 'Stop'
$created = [System.Collections.Generic.List[string]]::new()
function Kube {
  param([string[]]$Arguments)
  $result = & kubectl -n $Namespace @Arguments
  if ($LASTEXITCODE -ne 0) { throw "kubectl failed: $Arguments" }
  return $result
}
function Probe([string]$app) {
  $name = 'security-test-' + [guid]::NewGuid().ToString('N').Substring(0,8)
  $pod = @{
    apiVersion='v1'; kind='Pod'; metadata=@{name=$name; labels=@{app=$app}}
    spec=@{
      automountServiceAccountToken=$false; restartPolicy='Never'
      securityContext=@{runAsNonRoot=$true;runAsUser=10001;runAsGroup=10001;seccompProfile=@{type='RuntimeDefault'}}
      containers=@(@{name='probe';image='busybox:1.37';command=@('sleep','1800')
        securityContext=@{allowPrivilegeEscalation=$false;readOnlyRootFilesystem=$true;capabilities=@{drop=@('ALL')}}
        resources=@{requests=@{cpu='10m';memory='8Mi'};limits=@{cpu='100m';memory='32Mi'}}
        readinessProbe=@{exec=@{command=@('false')};periodSeconds=2}
      })
    }
  }
  $pod | ConvertTo-Json -Depth 20 | kubectl -n $Namespace create -f - | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Cannot create probe' }
  $created.Add($name)
  Kube @('wait',"pod/$name",'--for=jsonpath={.status.phase}=Running','--timeout=120s') | Out-Host
  return $name
}
function Tcp([string]$source,[string]$target,[int]$port,[bool]$allowed) {
  $ip = Kube @('get','svc',$target,'-o','jsonpath={.spec.clusterIP}')
  Kube @('exec',$source,'--','true') | Out-Null
  & kubectl -n $Namespace exec $source -- nc -z -w 3 $ip $port 2>&1 | Out-Host
  $code = $LASTEXITCODE
  if ($allowed -and $code -ne 0) { throw "FAIL allow $source -> ${target}:$port" }
  if (-not $allowed -and $code -ne 1) { throw "FAIL deny (unexpected exit $code) $source -> ${target}:$port" }
  Write-Host "PASS expectedAllowed=$allowed $source -> ${target}:$port"
}
Start-Transcript -Path $Log
try {
  foreach ($name in @('gateway','customer-service','fleet-service','booking-service','payment-service','customer-db','fleet-db','booking-db','payment-db')) {
    Kube @('rollout','status',"deployment/$name",'--timeout=300s') | Out-Host
    $pods = (Kube @('get','pods','-l',"app=$name",'-o','json') | ConvertFrom-Json).items | Where-Object { -not $_.metadata.deletionTimestamp }
    foreach ($pod in $pods) {
      $spec = $pod.spec
      if ($spec.automountServiceAccountToken -ne $false -or $spec.securityContext.runAsNonRoot -ne $true) { throw "Invalid pod context: $name" }
      foreach ($container in $spec.containers) {
        $sc=$container.securityContext
        if ($sc.allowPrivilegeEscalation -ne $false -or $sc.readOnlyRootFilesystem -ne $true) { throw "Invalid container context: $name" }
        Kube @('exec',$pod.metadata.name,'-c',$container.name,'--','sh','-c',
          'test "$(id -u)" != 0 && test ! -e /var/run/secrets/kubernetes.io/serviceaccount/token && grep -q "NoNewPrivs:[[:space:]]*1" /proc/1/status && test -w /tmp && ! touch /security-root-test 2>/dev/null') | Out-Host
      }
    }
    Write-Host "PASS runtime security $name"
  }
  foreach ($path in @('/api/customers','/api/vehicles','/api/bookings','/api/payments')) {
    $response=Invoke-WebRequest -Uri ($IngressUrl.TrimEnd('/')+$path) -Headers @{Host=$IngressHost}
    if ($response.StatusCode -ne 200) { throw "Ingress failed: $path" }
    Write-Host "PASS Ingress $path HTTP 200"
  }
  $outsider=Probe 'unauthorized'
  $gateway=Probe 'gateway'
  foreach ($name in @('customer','fleet','booking','payment')) {
    $owner=Probe "$name-service"
    Tcp $owner "$name-db" 5432 $true
    Tcp $outsider "$name-db" 5432 $false
    Tcp $gateway "$name-db" 5432 $false
    Tcp $gateway "$name-service" 8080 $true
    Tcp $outsider "$name-service" 8080 $false
    foreach ($other in @('customer','fleet','booking','payment')) {
      if ($other -ne $name) { Tcp $owner "$other-db" 5432 $false }
    }
    if ($name -eq 'booking') { Tcp $owner 'gateway' 8080 $true }
  }
  Tcp $outsider 'gateway' 8080 $false
  Write-Host 'PASS all runtime tests'
} finally {
  foreach ($name in $created) { kubectl -n $Namespace delete pod $name --wait=false | Out-Host }
  Stop-Transcript
}
