"""Run real-service contract/mutation checks and three k6 scenarios. Python stdlib only."""
import argparse
import contextlib
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'docs/evidence/contract-load'
PROTO = ROOT / 'grpc-contracts/src/main/proto/fleet.proto'
CONSUMER = ROOT / 'booking-service/target/app.jar'
PROVIDER = ROOT / 'fleet-service/target/app.jar'
CONTRACT = ROOT / 'tests/contracts/booking-fleet-v1.json'


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def run(command, log, env=None):
    print(f'{now()} RUN {command}', flush=True)
    started = time.perf_counter()
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    record = {'command': list(map(str, command)), 'exitCode': result.returncode,
              'durationSeconds': time.perf_counter() - started, 'log': str(log.relative_to(ROOT))}
    print(f'{now()} EXIT {result.returncode}: {log.name}', flush=True)
    return record


def http(base, path, body=None):
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=5) as response:
        return json.load(response)


@contextlib.contextmanager
def stack(java, folder, ports):
    folder.mkdir(parents=True, exist_ok=True)
    fleet_port, booking_port, grpc_port = ports
    # Refuse to reuse somebody else's running service.
    for port in ports:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    fleet_url, booking_url = f'http://127.0.0.1:{fleet_port}', f'http://127.0.0.1:{booking_port}'
    processes, logs = [], []
    try:
        for name, jar, port, extra in [
            ('fleet', PROVIDER, fleet_port, [f'--spring.grpc.server.port={grpc_port}']),
            ('booking', CONSUMER, booking_port, ['--spring.profiles.active=grpc',
             f'--fleet.grpc.target=127.0.0.1:{grpc_port}', '--messaging.enabled=false', '--spring.kafka.admin.auto-create=false']),
        ]:
            log = (folder / f'{name}.log').open('w', encoding='utf-8')
            logs.append(log)
            command = [str(java), '-Xms128m', '-Xmx512m', '-jar', str(jar), f'--server.port={port}',
                       '--server.address=127.0.0.1', f'--DB_URL=jdbc:h2:mem:{name};MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
                       '--DB_USER=sa', '--DB_PASSWORD=', '--logging.structured.format.console=',
                       '--logging.level.ua.carrental.fleet.grpc.GrpcAttemptLogging=WARN', *extra]
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            processes.append(process)
        deadline = time.monotonic() + 90
        for base, process in zip((fleet_url, booking_url), processes):
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'{base} process exited: see {folder}')
                try:
                    if http(base, '/actuator/health/readiness')['status'] == 'UP':
                        break
                except (URLError, OSError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError(f'{base} not ready: see {folder}')
                time.sleep(0.3)
        spec = json.loads(CONTRACT.read_text())
        cars = [http(fleet_url, '/api/vehicles', vehicle) for vehicle in spec['providerState']['vehicles']]
        context = {'fleetUrl': fleet_url, 'bookingUrl': booking_url, 'grpcPort': grpc_port,
                   'vehicleIds': [car['id'] for car in cars], 'providerPid': processes[0].pid,
                   'consumerPid': processes[1].pid, 'readyAtUtc': now(),
                   'providerJarSha256': sha(PROVIDER), 'consumerJarSha256': sha(CONSUMER)}
        save(folder / 'context.json', context)
        yield context
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in logs:
            log.close()


def contract_run(java, folder, ports):
    with stack(java, folder, ports):
        env = dict(os.environ, CONTRACT_CONTEXT=str(folder / 'context.json'), CONTRACT_RESULT=str(folder / 'result.json'))
        record = run([sys.executable, str(ROOT / 'tests/contracts/test_booking_fleet_contract.py')], folder / 'tests.log', env)
        record['result'] = json.loads((folder / 'result.json').read_text())
        return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--k6', default=os.environ.get('K6_EXE', 'k6'))
    parser.add_argument('--skip-build', action='store_true', help='Use existing clean-verified JARs')
    parser.add_argument('--contracts-only', action='store_true')
    parser.add_argument('--ports', type=int, nargs=3, default=[28082, 28083, 29090], metavar=('FLEET', 'BOOKING', 'GRPC'))
    args = parser.parse_args()
    java = Path(args.java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if args.java_home else Path(shutil.which('java') or 'java')
    env = dict(os.environ)
    if args.java_home:
        env.update(JAVA_HOME=args.java_home, PATH=str(java.parent) + os.pathsep + env.get('PATH', ''))
    maven = str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    summary = {'startedAtUtc': now(), 'platform': platform.platform(), 'logicalCpus': os.cpu_count(),
               'python': sys.version, 'java': subprocess.run([str(java), '-version'], capture_output=True, text=True).stderr,
               'commands': [], 'ports': args.ports}
    original = PROTO.read_bytes()
    restore_required = False
    try:
        if not args.skip_build:
            build = run([maven, '-B', '-Pgrpc-demo', 'clean', 'verify'], EVIDENCE / 'maven-verify.log', env)
            summary['commands'].append(build)
            if build['exitCode']:
                raise RuntimeError('Baseline Maven verification failed')
        consumer_sha, contract_sha = sha(CONSUMER), sha(CONTRACT)
        summary['consumerJarSha256'] = consumer_sha
        summary['baseline'] = contract_run(java, EVIDENCE / 'baseline', args.ports)
        if summary['baseline']['exitCode']:
            raise RuntimeError('Baseline contract failed')
        needle = b'string daily_rate = 5;'
        if original.count(needle) != 1:
            raise RuntimeError('Expected exactly one daily_rate field to mutate')
        restore_required = True
        PROTO.write_bytes(original.replace(needle, b'reserved 5;\n  string daily_rate = 15;'))
        summary['mutation'] = {'change': 'VehicleReply.daily_rate wire field 5 -> 15; field 5 reserved',
                               'side': 'fleet-service only; booking JAR not rebuilt', 'originalProtoSha256': hashlib.sha256(original).hexdigest(),
                               'mutatedProtoSha256': sha(PROTO)}
        (EVIDENCE / 'mutated-provider.proto').write_bytes(PROTO.read_bytes())
        build_command = [maven, '-B', '-Pgrpc-demo', '-pl', 'fleet-service', '-am', '-DskipTests', 'clean', 'package']
        mutation_build = run(build_command, EVIDENCE / 'mutation-build.log', env)
        summary['commands'].append(mutation_build)
        if mutation_build['exitCode']:
            raise RuntimeError('Mutated provider failed to compile (not a contract test result)')
        if sha(CONSUMER) != consumer_sha or sha(CONTRACT) != contract_sha:
            raise RuntimeError('Consumer artifact or contract expectations changed')
        summary['broken'] = contract_run(java, EVIDENCE / 'broken', args.ports)
        result = summary['broken']['result']
        expected_failures = {'test_unary_vehicle_contract', 'test_server_stream_vehicle_contract'}
        actual_failures = {failure['test'].split('.')[-1] for failure in result['failures']}
        if summary['broken']['exitCode'] != 1 or result['tests'] != 6 or actual_failures != expected_failures or result['errors']:
            raise RuntimeError('Mutation did not produce exactly the expected two contract assertion failures')
        PROTO.write_bytes(original)
        restore_build = run(build_command, EVIDENCE / 'restore-build.log', env)
        summary['commands'].append(restore_build)
        if restore_build['exitCode']:
            raise RuntimeError('Restored provider build failed')
        restore_required = False
        if sha(CONSUMER) != consumer_sha or sha(CONTRACT) != contract_sha:
            raise RuntimeError('Consumer artifact or contract expectations changed')
        summary['restored'] = contract_run(java, EVIDENCE / 'restored', args.ports)
        if summary['restored']['exitCode']:
            raise RuntimeError('Restored contract failed')
        summary['mutation']['consumerAndExpectationsUnchanged'] = True
        summary['mutation']['sourceRestored'] = PROTO.read_bytes() == original
        if not args.contracts_only:
            summary['k6Version'] = subprocess.check_output([args.k6, 'version'], text=True).strip()
            load_dir = EVIDENCE / 'load'
            summary['load'] = {}
            with stack(java, load_dir, args.ports) as context:
                # Warm all four RPC paths; these requests are excluded from measured scenarios.
                items = [dict(requestId='a', vehicleId=context['vehicleIds'][0], days=2),
                         dict(requestId='b', vehicleId=context['vehicleIds'][1], days=3)]
                for _ in range(25):
                    http(context['bookingUrl'], '/api/grpc/vehicles/' + context['vehicleIds'][0])
                    http(context['bookingUrl'], '/api/grpc/vehicles')
                    http(context['bookingUrl'], '/api/grpc/quotes/client-stream', items)
                    http(context['bookingUrl'], '/api/grpc/quotes/bidi', items)
                for scenario in ('steady', 'ramp', 'spike'):
                    scenario_dir = load_dir / scenario
                    scenario_dir.mkdir(exist_ok=True)
                    raw = scenario_dir / 'metrics.json'
                    load_env = dict(os.environ, SCENARIO=scenario, CONTEXT=str(load_dir / 'context.json'),
                                    SUMMARY=str(scenario_dir / 'summary.json'), K6_NO_USAGE_REPORT='true')
                    started = now()
                    record = run([args.k6, 'run', '--out', 'json=' + str(raw), str(ROOT / 'tests/load/scenarios.js')],
                                 scenario_dir / 'k6.log', load_env)
                    record.update(startedAtUtc=started, finishedAtUtc=now())
                    summary['load'][scenario] = record
                    if raw.exists():
                        with raw.open('rb') as source, gzip.open(str(raw) + '.gz', 'wb') as target:
                            shutil.copyfileobj(source, target)
                        raw.unlink()
                    if record['exitCode'] not in (0, 99):
                        raise RuntimeError(f'{scenario}: k6 execution error')
                    # Threshold failures remain visible but do not prevent the other scenarios running.
                    save(EVIDENCE / 'run-summary.json', summary)
                    time.sleep(3)
        summary['completed'] = True
    except Exception as error:
        summary['completed'] = False
        summary['error'] = repr(error)
        raise
    finally:
        if restore_required:
            PROTO.write_bytes(original)
            rescue = run([maven, '-B', '-Pgrpc-demo', '-pl', 'fleet-service', '-am', '-DskipTests', 'clean', 'package'],
                         EVIDENCE / 'emergency-restore-build.log', env)
            summary['emergencyRestore'] = rescue
        summary['finishedAtUtc'] = now()
        save(EVIDENCE / 'run-summary.json', summary)
    return 0 if all(v['exitCode'] == 0 for v in summary.get('load', {}).values()) else 1


if __name__ == '__main__':
    sys.exit(main())
