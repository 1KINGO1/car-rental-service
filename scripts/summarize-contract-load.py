"""Validate saved raw k6 samples against summaries and export comparison CSV/JSON."""
import csv
import gzip
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'docs/evidence/contract-load'


def percentile(values, percent):
    values = sorted(values)
    pos = (len(values) - 1) * percent / 100
    lo, hi = math.floor(pos), math.ceil(pos)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def main():
    rows, analysis = [], {}
    for name in ('steady', 'ramp', 'spike'):
        folder = EVIDENCE / 'load' / name
        summary = json.loads((folder / 'summary.json').read_text())
        metrics = summary['metrics']
        durations, groups = [], {}
        request_count, http_errors, payload_errors = 0, 0, 0
        with gzip.open(folder / 'metrics.json.gz', 'rt', encoding='utf-8') as source:
            for line in source:
                sample = json.loads(line)
                if sample['type'] != 'Point':
                    continue
                metric, data = sample['metric'], sample['data']
                if metric == 'http_reqs':
                    request_count += int(data['value'])
                elif metric == 'http_req_failed':
                    http_errors += int(data['value'])
                elif metric == 'contract_errors':
                    payload_errors += int(data['value'])
                elif metric == 'http_req_duration':
                    durations.append(data['value'])
                    for key in ('operation', 'phase'):
                        groups.setdefault(key + ':' + data['tags'][key], []).append(data['value'])
        assert request_count == metrics['http_reqs']['values']['count'] == len(durations), name
        assert math.isclose(percentile(durations, 95), metrics['http_req_duration']['values']['p(95)'], rel_tol=1e-9), name
        assert math.isclose(http_errors / request_count, metrics['http_req_failed']['values']['rate'], abs_tol=1e-12), name
        assert math.isclose(payload_errors / request_count, metrics['contract_errors']['values']['rate'], abs_tol=1e-12), name
        threshold_count = sum(len(m.get('thresholds', {})) for m in metrics.values())
        failed_thresholds = [f'{metric}: {threshold}' for metric, m in metrics.items()
                             for threshold, value in m.get('thresholds', {}).items() if not value['ok']]
        trend = metrics['http_req_duration']['values']
        row = dict(scenario=name, requests=request_count, duration_s=summary['state']['testRunDurationMs'] / 1000,
                   requests_per_second=metrics['http_reqs']['values']['rate'], avg_ms=trend['avg'],
                   median_ms=trend['med'], p90_ms=trend['p(90)'], p95_ms=trend['p(95)'], p99_ms=trend['p(99)'],
                   max_ms=trend['max'], http_errors=http_errors, contract_errors=payload_errors,
                   checks_passed=metrics['checks']['values']['passes'], checks_failed=metrics['checks']['values']['fails'],
                   thresholds=threshold_count, thresholds_failed=len(failed_thresholds))
        rows.append(row)
        analysis[name] = dict(row, failed_thresholds=failed_thresholds,
                             groups={key: {'requests': len(v), 'avg_ms': sum(v) / len(v), 'p95_ms': percentile(v, 95),
                                           'p99_ms': percentile(v, 99), 'max_ms': max(v)} for key, v in groups.items()})
    with (EVIDENCE / 'load-results.csv').open('w', newline='', encoding='utf-8-sig') as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    (EVIDENCE / 'load-analysis.json').write_text(json.dumps(analysis, indent=2), encoding='utf-8')
    print(json.dumps(analysis, indent=2))


if __name__ == '__main__':
    main()
