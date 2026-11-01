param([string]$IngressUrl='http://127.0.0.1:18080', [string]$IngressHost='car-rental.local')
$ErrorActionPreference='Stop'
$headers=@{Host=$IngressHost;'Idempotency-Key'=[guid]::NewGuid().ToString()}
function PostJson($path,$body) {
  $response=Invoke-WebRequest -Uri ($IngressUrl+$path) -Method Post -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json)
  if ($response.StatusCode -ne 201) { throw "Expected HTTP 201: $path" }
  return ($response.Content | ConvertFrom-Json)
}
$tag=[guid]::NewGuid().ToString('N').Substring(0,8)
$customer=PostJson '/api/customers' @{name='Security Test';email="security-$tag@example.test";verified=$true}
$vehicle=PostJson '/api/vehicles' @{brand='Test';model='Security';plate="TEST-$tag";dailyRate=100}
$start=[DateTime]::UtcNow.Date.AddDays(1)
$booking=PostJson '/api/bookings' @{customerId=$customer.id;vehicleId=$vehicle.id;startDate=$start.ToString('yyyy-MM-dd');endDate=$start.AddDays(2).ToString('yyyy-MM-dd')}
$read=Invoke-RestMethod -Uri ($IngressUrl+'/api/bookings/'+$booking.id) -Headers $headers
if ($read.id -ne $booking.id) { throw 'Booking readback mismatch' }
@{result='PASS';customerId=$customer.id;vehicleId=$vehicle.id;bookingId=$booking.id;flow='Ingress -> gateway -> booking -> gateway -> customer/fleet; database write and read'} | ConvertTo-Json
