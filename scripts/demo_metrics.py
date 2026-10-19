"""Exercise real Kubernetes services and save all five Micrometer meter types.
Run: python scripts/demo_metrics.py --namespace car-rental-lab06
Requires kubectl on PATH and the monitoring-demo profile in the lab deployment.
"""
import argparse
import concurrent.futures
import datetime as dt
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--namespace', default='car-rental-lab06')
    parser.add_argument('--output', default='docs/evidence/metrics')
    args = parser.parse_args()
    out = pathlib.Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    services = {'customer': (18081, 'customers'), 'fleet': (18082, 'vehicles'),
                'booking': (18083, 'bookings'), 'payment': (18084, 'payments')}
    forwards, logs = [], []

    def request(service, path, data=None, key=None, timeout=45):
        headers = {}
        if data is not None:
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(f'http://127.0.0.1:{services[service][0]}{path}',
                                     data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read().decode(), dict(response.headers)

    def scrape(service, stage):
        body, headers = request(service, '/actuator/prometheus')
        (out / f'{service}-{stage}.prom').write_text(body, encoding='utf-8')
        (out / f'{service}-{stage}-headers.json').write_text(json.dumps(headers, indent=2), encoding='utf-8')
        return body

    def value(body, name, **labels):
        total = 0.0
        for line in body.splitlines():
            if re.match(re.escape(name) + r'(\{|\s)', line):
                if all(f'{k}="{v}"' in line for k, v in labels.items()):
                    total += float(line.split()[-1])
        return total

    try:
        for service, (port, _) in services.items():
            log = (out / f'{service}-port-forward.log').open('w', encoding='utf-8')
            logs.append(log)
            forwards.append(subprocess.Popen(['kubectl', '-n', args.namespace, 'port-forward',
                f'svc/{service}-service', f'{port}:8080', '--address=127.0.0.1'],
                stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)))
        for service in services:
            for attempt in range(45):
                try:
                    request(service, '/actuator/health/readiness', timeout=2)
                    break
                except Exception:
                    if attempt == 44:
                        raise
                    time.sleep(1)
        before = {service: scrape(service, 'before') for service in services}
        run_id = uuid.uuid4().hex[:8]
        customer = json.loads(request('customer', '/api/customers',
            {'name': 'Lab06 Metrics', 'email': f'lab06-{run_id}@example.test', 'verified': True})[0])
        vehicle = json.loads(request('fleet', '/api/vehicles',
            {'brand': 'Lab', 'model': 'Monitoring', 'plate': 'LAB'+run_id, 'dailyRate': 1000})[0])
        start = dt.date.today() + dt.timedelta(days=1)
        booking = json.loads(request('booking', '/api/bookings',
            {'customerId': customer['id'], 'vehicleId': vehicle['id'], 'startDate': str(start),
             'endDate': str(start + dt.timedelta(days=2))}, 'lab06-book-'+run_id)[0])
        payment = json.loads(request('payment', '/api/payments',
            {'bookingId': booking['id'], 'amount': 2000, 'currency': 'UAH'}, 'lab06-pay-'+run_id)[0])
        for service, (_, route) in services.items():
            for _ in range(5):
                request(service, '/api/' + route)
        (out / 'created-business-entities.json').write_text(json.dumps(
            dict(customer=customer, vehicle=vehicle, booking=booking, payment=payment), indent=2), encoding='utf-8')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            pending = {s: pool.submit(request, s, '/api/monitoring/demo-work?seconds=35') for s in services}
            time.sleep(12)
            active = {s: scrape(s, 'active') for s in services}
            for service in services:
                assert value(active[service], 'car_rental_http_in_flight_requests') >= 1, service
                assert value(active[service], 'car_rental_http_active_requests_seconds_count') >= 1, service
                assert value(active[service], 'car_rental_http_active_requests_seconds_sum') >= 10, service
            for future in pending.values():
                future.result(timeout=45)
        after = {s: scrape(s, 'after') for s in services}
        summary = {'capturedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'namespace': args.namespace, 'services': {}}
        for service in services:
            metrics = {
                'counterBefore': value(before[service], 'car_rental_http_requests_total'),
                'counterAfter': value(after[service], 'car_rental_http_requests_total'),
                'gaugeActive': value(active[service], 'car_rental_http_in_flight_requests'),
                'gaugeAfter': value(after[service], 'car_rental_http_in_flight_requests'),
                'timerCountBefore': value(before[service], 'car_rental_http_request_duration_seconds_count'),
                'timerCountAfter': value(after[service], 'car_rental_http_request_duration_seconds_count'),
                'timerSecondsAfter': value(after[service], 'car_rental_http_request_duration_seconds_sum'),
                'longTaskActiveCount': value(active[service], 'car_rental_http_active_requests_seconds_count'),
                'longTaskActiveSeconds': value(active[service], 'car_rental_http_active_requests_seconds_sum'),
                'longTaskCountAfter': value(after[service], 'car_rental_http_active_requests_seconds_count'),
                'summaryBytesBefore': value(before[service], 'car_rental_http_request_body_bytes_sum'),
                'summaryBytesAfter': value(after[service], 'car_rental_http_request_body_bytes_sum'),
                'committedCreations': value(after[service], 'car_rental_business_total')}
            assert metrics['counterAfter'] > metrics['counterBefore'], service
            assert metrics['timerCountAfter'] > metrics['timerCountBefore'], service
            assert metrics['timerSecondsAfter'] >= 35, service
            assert metrics['summaryBytesAfter'] > metrics['summaryBytesBefore'], service
            assert metrics['gaugeAfter'] == metrics['longTaskCountAfter'] == 0, service
            assert 'car_rental_http_request_duration_seconds_bucket{' in after[service], service
            assert 'car_rental_http_request_body_bytes_bucket{' in after[service], service
            summary['services'][service] = metrics
        (out / 'metrics-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        print(json.dumps(summary, indent=2))
        print('PASS: all five meter types observed on all four live Kubernetes services.')
    finally:
        for process in forwards:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
