"""Black-box consumer contract; requires both real services and seeded provider state.

Run via scripts/run-contract-load.py, or set CONTRACT_CONTEXT to its context.json.
Expectations are independent of generated provider classes and are never mutated.
"""
import json
import os
from pathlib import Path
import unittest
import sys
import time
from decimal import Decimal
from urllib.request import Request, urlopen
from urllib.error import HTTPError

CONTRACT = json.loads(Path(__file__).with_name('booking-fleet-v1.json').read_text())


def request(base, path, body=None):
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.loads(error.read())


class BookingFleetContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = json.loads(Path(os.environ['CONTRACT_CONTEXT']).read_text())
        cls.base = cls.context['bookingUrl']
        cls.ids = cls.context['vehicleIds']
        cls.expected = CONTRACT['expected']
        cls.items = [dict(requestId='a', vehicleId=cls.ids[0], days=2),
                     dict(requestId='b', vehicleId=cls.ids[1], days=3)]

    def verify_vehicle(self, actual, index):
        self.assertEqual(actual['id'], self.ids[index])
        for field in ('brand', 'model', 'plate'):
            self.assertEqual(actual[field], CONTRACT['providerState']['vehicles'][index][field])
        self.assertEqual(Decimal(str(actual['dailyRate'])),
                         Decimal(str(CONTRACT['providerState']['vehicles'][index]['dailyRate'])))

    def test_unary_vehicle_contract(self):
        status, body = request(self.base, '/api/grpc/vehicles/' + self.ids[0])
        self.assertEqual(status, self.expected['unaryStatus'], body)
        self.verify_vehicle(body, 0)

    def test_server_stream_vehicle_contract(self):
        status, body = request(self.base, '/api/grpc/vehicles')
        self.assertEqual(status, self.expected['listStatus'], body)
        self.assertEqual(len(body), len(self.ids))
        self.assertEqual({v['id'] for v in body}, set(self.ids))
        for item in body:
            self.verify_vehicle(item, self.ids.index(item['id']))

    def test_client_stream_quote_contract(self):
        status, body = request(self.base, '/api/grpc/quotes/client-stream', self.items)
        self.assertEqual(status, self.expected['quoteStatus'], body)
        self.assertEqual(body, {key: self.expected[key] for key in ('itemCount', 'total', 'currency')})

    def test_bidi_quote_contract(self):
        status, body = request(self.base, '/api/grpc/quotes/bidi', self.items)
        self.assertEqual(status, self.expected['quoteStatus'], body)
        self.assertEqual(len(body), 2)
        for index, line in enumerate(body):
            self.assertEqual(line, dict(self.items[index], amount=self.expected['lineAmounts'][index],
                                        currency=self.expected['currency']))

    def test_missing_vehicle_contract(self):
        status, _ = request(self.base, '/api/grpc/vehicles/00000000-0000-4000-8000-000000000000')
        self.assertEqual(status, self.expected['missingVehicleStatus'])

    def test_invalid_days_contract(self):
        status, _ = request(self.base, '/api/grpc/quotes/client-stream', [dict(self.items[0], days=0)])
        self.assertEqual(status, self.expected['invalidDaysStatus'])


if __name__ == '__main__':
    started = time.perf_counter()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BookingFleetContractTest))
    if os.environ.get('CONTRACT_RESULT'):
        Path(os.environ['CONTRACT_RESULT']).write_text(json.dumps({
            'tests': result.testsRun, 'failures': [{'test': t.id(), 'traceback': tb} for t, tb in result.failures],
            'errors': [{'test': t.id(), 'traceback': tb} for t, tb in result.errors],
            'skipped': len(result.skipped), 'durationSeconds': time.perf_counter() - started,
            'passed': result.wasSuccessful(),
        }, indent=2))
    sys.exit(0 if result.wasSuccessful() else 1)
