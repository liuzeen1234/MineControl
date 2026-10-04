#!/usr/bin/env python3
"""Requires a disposable client configured with maxHoldTicks=10, requestTimeoutMs=15000."""
import importlib.util
import json
from pathlib import Path
import socket
import time
import uuid
spec = importlib.util.spec_from_file_location('minectl', Path(__file__).resolve().parents[1] / 'cli/minectl.py')
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
checks = []

def request(command, **values):
    response = cli.exchange({'id':uuid.uuid4().hex,'command':command,**values},25580,20)
    assert response['ok'], response
    return response['result']

request('input.reset')
request('key.down', key='W'); time.sleep(.8)
assert request('status')['held_keys'] == []
request('mouse.down', button='right'); time.sleep(.8)
assert request('status')['held_buttons'] == []
checks.append('maximum hold cap releases keyboard and mouse down'); print('PASS',checks[-1],flush=True)
with socket.create_connection(('127.0.0.1',25580),3) as connection:
    connection.settimeout(3)
    connection.sendall(b' ' * 65537 + b'\n')
    with connection.makefile('rb') as reader:
        response = json.loads(reader.readline())
        assert response['error']['code'] == 'REQUEST_TOO_LARGE', response
checks.append('oversized request returns error and closes connection'); print('PASS',checks[-1],flush=True)
with socket.create_connection(('127.0.0.1',25580),3) as connection:
    connection.settimeout(3)
    connection.sendall(b'{"id":true,"command":"ping"}\n')
    with connection.makefile('rb') as reader:
        response = json.loads(reader.readline())
        assert response['error']['code'] == 'INVALID_REQUEST' and response['id'] is None, response
checks.append('boolean request id rejected'); print('PASS',checks[-1],flush=True)
with socket.create_connection(('127.0.0.1',25580),3) as idle:
    idle.sendall(b'{')
    start = time.monotonic(); request('ping'); elapsed = time.monotonic()-start
    assert 14 <= elapsed < 20, elapsed
checks.append('idle partial request expires and next client succeeds'); print('PASS',checks[-1],elapsed,flush=True)
Path('docs/live-reliability-results.json').write_text(json.dumps(checks,indent=2)+'\n')
