#!/usr/bin/env python3
"""Exercise a running, disposable development client. Start at TitleScreen."""
import importlib.util
from pathlib import Path
import json
import socket
import time
import uuid

spec = importlib.util.spec_from_file_location('minectl', Path(__file__).resolve().parents[1] / 'cli/minectl.py')
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
results = []


def request(command, expected=None, **values):
    response = cli.exchange({'id': uuid.uuid4().hex, 'command': command, **values}, 25580, 20)
    if expected:
        assert not response['ok'] and response['error']['code'] == expected, response
    else:
        assert response['ok'], response
    return response.get('result', response.get('error'))


def click(label):
    widgets = request('ui.list')
    widget = next(w for w in widgets if w['label'] == label)
    return request('ui.click', widget=widget['id'])


def check(name, test):
    test(); results.append(name); print('PASS', name, flush=True)


def wire_test():
    with socket.create_connection(('127.0.0.1', 25580), 3) as connection:
        connection.settimeout(20)
        with connection.makefile('rb') as reader:
            for chunk in [b'{"id":"split","com', b'mand":"ping"}\n{"id":"second","command":"status"}\n']:
                connection.sendall(chunk)
            for identifier in ['split', 'second']:
                response = json.loads(reader.readline()); assert response['id'] == identifier and response['ok'], response
            connection.sendall(b'not json\n{"id":"bad","command":"missing"}\n')
            assert json.loads(reader.readline())['error']['code'] == 'INVALID_REQUEST'
            assert json.loads(reader.readline())['error']['code'] == 'UNKNOWN_COMMAND'


check('ping', lambda: request('ping'))
check('split packets, multiple requests and malformed JSON', wire_test)
check('invalid key', lambda: request('key.tap', expected='UNKNOWN_KEY', key='NOT_A_KEY'))
check('invalid hold', lambda: request('key.hold', expected='INVALID_ARGUMENT', key='W', ticks=0))
check('screen timeout', lambda: request('wait.screen', expected='TIMEOUT', screen='NeverExists', timeout_ms=100))
assert request('status')['screen'].endswith('TitleScreen')
check('click options', lambda: click('Options...'))
check('wait options screen', lambda: request('wait.screen', screen='OptionsScreen', timeout_ms=1000))
request('key.tap', key='ESCAPE'); time.sleep(.15)
assert request('status')['screen'].endswith('TitleScreen')
results.append('keyboard Escape closes options')
widgets = request('ui.list'); options = next(w for w in widgets if w['label'] == 'Options...')
request('mouse.goto', x=options['x'] + options['width']/2, y=options['y'] + options['height']/2)
request('mouse.click', button='left'); time.sleep(.2)
assert request('status')['screen'].endswith('OptionsScreen')
results.append('scaled mouse coordinates and left click')
request('key.tap', key='ESCAPE'); time.sleep(.15)
request('key.down', key='LEFT_SHIFT'); request('key.down', key='W')
assert set(request('status')['held_keys']) == {340, 87}
request('key.up', key='W'); assert request('status')['held_keys'] == [340]
request('input.reset'); assert request('status')['held_keys'] == []
results.append('persistent combination, up and reset')
request('key.hold', key='W', ticks=3); time.sleep(.3)
assert request('status')['held_keys'] == []
request('mouse.hold', button='right', ticks=3); time.sleep(.3)
assert request('status')['held_buttons'] == []
results.append('keyboard and mouse timed release')
request('mouse.down', button='right'); assert request('status')['held_buttons'] == [1]
request('mouse.up', button='right'); assert request('status')['held_buttons'] == []
results.append('right button down/up')
check('click singleplayer', lambda: click('Singleplayer'))
# With no worlds Minecraft opens CreateWorldScreen asynchronously.
request('wait.screen', screen='CreateWorldScreen', timeout_ms=14000)
widgets = request('ui.list'); print(json.dumps(widgets, ensure_ascii=False, indent=2))
fields = [w for w in widgets if 'text' in w]
assert fields
field = fields[0]
request('ui.type', widget=field['id'], text='MineControl Test')
request('ui.type', widget=field['id'], text=' 世界', mode='append')
assert next(w for w in request('ui.list') if w['id'] == field['id'])['text'] == 'MineControl Test 世界'
results.append('text replacement and Unicode append')
request('ui.click', expected='DISABLED_WIDGET', widget=5)
request('ui.click', expected='STALE_WIDGET', widget=9999)
results.append('disabled and stale widgets rejected')
boolean = next(w for w in request('ui.list') if isinstance(w.get('value'), bool))
request('ui.set', widget=boolean['id'], value=True)
request('ui.set', widget=boolean['id'], value=True)
assert next(w for w in request('ui.list') if w['id'] == boolean['id'])['value'] is True
request('ui.set', widget=boolean['id'], value=False)
assert next(w for w in request('ui.list') if w['id'] == boolean['id'])['value'] is False
results.append('idempotent boolean cycling ui set')
click('World')
assert any(w['label'].startswith('World Type:') for w in request('ui.list'))
click('Game')
assert any(w.get('text') == 'MineControl Test 世界' for w in request('ui.list'))
results.append('nested tab controls')
Path('docs/live-smoke-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
print('PASS', len(results), 'live checks; left on CreateWorldScreen')
