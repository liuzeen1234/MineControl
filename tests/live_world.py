#!/usr/bin/env python3
"""Run on a disposable loaded world, with the player standing on level ground."""
import importlib.util
import json
import math
from pathlib import Path
import time
import uuid

spec = importlib.util.spec_from_file_location('minectl', Path(__file__).resolve().parents[1] / 'cli/minectl.py')
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
records = []


def request(command, **values):
    response = cli.exchange({'id': uuid.uuid4().hex, 'command': command, **values}, 25580, 20)
    assert response['ok'], response
    return response['result']


def status(): return request('status')
def distance(a, b): return math.hypot(a['player_x'] - b['player_x'], a['player_z'] - b['player_z'])
def record(name, **values):
    records.append({'check': name, **values}); print('PASS', name, values, flush=True)

request('input.reset')
start = status(); assert start['world_loaded'] and start['screen'] is None, start
request('key.down', key='W'); time.sleep(.5)
moving = status(); assert distance(start, moving) > .2, (start, moving)
assert moving['held_keys'] == [87]
request('key.up', key='W'); time.sleep(.4)
stopped = status(); time.sleep(.4); after = status()
assert distance(stopped, after) < .05, (stopped, after)
record('W down persists across CLI connections, moves player, up stops', distance=distance(start, moving), drift=distance(stopped, after), focused=moving['window_focused'])
start = status(); request('key.hold', key='W', ticks=8); time.sleep(.7); after = status()
assert not after['held_keys'] and distance(start, after) > .2, (start, after)
record('timed movement releases', distance=distance(start, after))
request('key.down', key='LEFT_SHIFT'); request('key.down', key='W'); time.sleep(.3)
assert set(status()['held_keys']) == {340, 87}
request('input.reset'); assert status()['held_keys'] == []
record('gameplay combination and reset')
time.sleep(.5); before = status(); assert before['on_ground'], before
request('key.tap', key='SPACE'); time.sleep(.15); jumping = status()
assert jumping['player_y'] > before['player_y'] + .2, (before, jumping)
time.sleep(1); assert not status()['held_keys']
record('SPACE tap causes jump', rise=jumping['player_y'] - before['player_y'])
before = status(); request('mouse.move', dx=30, dy=-10); after = status()
assert abs((after['yaw'] - before['yaw']) - 4.5) < .01, (before, after)
assert abs((after['pitch'] - before['pitch']) + 1.5) < .01, (before, after)
record('mouse motion uses configured default sensitivity', yaw_delta=after['yaw']-before['yaw'], pitch_delta=after['pitch']-before['pitch'])
for button in ['left', 'right']:
    request('mouse.click', button=button); time.sleep(.15)
    assert not status()['held_buttons']
record('left and right gameplay clicks release')
request('key.tap', key='E'); request('wait.screen', screen='CreativeInventoryScreen', timeout_ms=2000)
request('key.tap', key='ESCAPE'); time.sleep(.15); assert status()['screen'] is None
record('E opens inventory, Escape returns to gameplay')
request('key.tap', key='F2'); time.sleep(.3)
request('key.tap', key='ESCAPE'); request('wait.screen', screen='GameMenuScreen', timeout_ms=2000)
request('key.down', key='W')
widgets = request('ui.list'); leave = next(w for w in widgets if w['label'] == 'Save and Quit to Title')
request('ui.click', widget=leave['id']); request('wait.screen', screen='TitleScreen', timeout_ms=14000)
state = status(); assert not state['world_loaded'] and not state['held_keys'] and not state['held_buttons'], state
record('leaving world releases held inputs')
Path('docs/live-world-results.json').write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n')
print('PASS', len(records), 'world checks; left at main menu')
