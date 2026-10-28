#!/usr/bin/env python3
"""HTTP walkthrough. Refuses non-local URLs and any database with existing accepted orders."""
import json
import os
import sys
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

base = os.environ.get('DEMO_URL', 'http://localhost:8080').rstrip('/')
parsed = urlparse(base)
if parsed.scheme not in {'http', 'https'} or parsed.hostname not in {'localhost', '127.0.0.1', '::1'} or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
    sys.exit('Demo requires a local origin such as http://localhost:8080; no data was changed.')

def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(base + path, data=data, method=method, headers={'Content-Type': 'application/json'})
    try:
        with urlopen(request, timeout=10) as response:
            return json.load(response)
    except HTTPError as error:
        sys.exit(f'HTTP {error.code}: {error.read().decode()}')
    except URLError as error:
        sys.exit(f'Cannot reach local engine: {error.reason}. Run docker compose up --build first.')

def order(side, price, qty):
    result = call('POST', '/api/orders', {'side': side, 'priceTicks': price, 'quantity': qty})
    print(json.dumps(result, separators=(',', ':')))
    return result

metrics = call('GET', '/api/metrics')
if metrics['acceptedOrders'] != 0:
    sys.exit('Demo requires an empty book AND empty history. Existing data was preserved. Use a separate checkout/Compose project and unused ports for a fresh demo.')
print('SIMULATED EXCHANGE — FIFO, partial fill, price sweep, cancellation')
a = order('SELL', 10000, 5)
b = order('SELL', 10000, 5)
fifo = order('BUY', 10000, 7)
assert [(t['makerOrderId'], t['quantity']) for t in fifo['executions']] == [(a['order']['id'], 5), (b['order']['id'], 2)]
assert call('GET', '/api/orders/' + b['order']['id'])['remainingQuantity'] == 3
c = order('SELL', 10001, 4)
sweep = order('BUY', 10001, 8)
assert [(t['priceTicks'], t['quantity']) for t in sweep['executions']] == [(10000, 3), (10001, 4)]
assert sweep['order']['remainingQuantity'] == 1
cancelled = call('DELETE', '/api/orders/' + sweep['order']['id'])
assert cancelled['order']['status'] == 'CANCELLED'
print('Cancellation:', json.dumps(cancelled, separators=(',', ':')))
book = call('GET', '/api/book')
assert not book['bids'] and not book['asks']
print('Final metrics:', json.dumps(call('GET', '/api/metrics'), separators=(',', ':')))
print('Walkthrough assertions passed. History was retained; nothing was reset.')
