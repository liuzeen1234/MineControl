#!/usr/bin/env python3
"""Run a bounded survival mining macro and measure client inventory net changes."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import time
from minectl import exchange


def mining_macro(end_tick, pitch_dy=200.0):
    safe = {'all': [
        {'field': 'tick', 'op': '<', 'value': end_tick},
        {'field': 'observe.active', 'value': True},
        {'field': 'observe.player.game_mode', 'value': 'survival'},
        {'field': 'observe.player.alive', 'value': True},
        {'field': 'observe.player.health', 'op': '>=', 'value': 16},
        {'field': 'observe.player.food', 'op': '>=', 'value': 4},
        {'field': 'observe.main_hand.id', 'value': 'minecraft:netherite_pickaxe'},
        {'field': 'observe.main_hand.remaining_durability', 'op': '>', 'value': 32},
        {'field': 'observe.target.kind', 'value': 'block'},
        {'any': [{'field': 'observe.target.id', 'value': 'minecraft:' + block} for block in
                 ['stone', 'deepslate', 'granite', 'diorite', 'andesite', 'tuff',
                  'coal_ore', 'iron_ore', 'copper_ore', 'gold_ore', 'redstone_ore', 'lapis_ore', 'diamond_ore', 'emerald_ore',
                  'deepslate_coal_ore', 'deepslate_iron_ore', 'deepslate_copper_ore', 'deepslate_gold_ore',
                  'deepslate_redstone_ore', 'deepslate_lapis_ore', 'deepslate_diamond_ore', 'deepslate_emerald_ore']]}
    ]}
    return [{'while': safe, 'do': [
        {'command': 'key.down', 'key': 'w'},
        {'command': 'mouse.down', 'button': 'left'},
        {'wait': 10},
        {'command': 'mouse.move', 'dx': 0, 'dy': pitch_dy},
        {'wait': 10},
        {'command': 'mouse.move', 'dx': 0, 'dy': -pitch_dy},
        {'command': 'mouse.up', 'button': 'left'},
        {'command': 'key.up', 'key': 'w'},
        {'wait': 1}
    ]}]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=25580)
    parser.add_argument('--seconds', type=float, default=600)
    parser.add_argument('--pitch-dy', type=float, default=200, help='30 degree mouse movement at default sensitivity; invert Y must be off')
    parser.add_argument('--out', type=Path, default=Path('mining-results'))
    args = parser.parse_args()
    if not math.isfinite(args.seconds) or not 1 <= args.seconds <= 3600:
        parser.error('--seconds must be 1..3600')
    if not math.isfinite(args.pitch_dy) or not 0 < args.pitch_dy <= 1000:
        parser.error('--pitch-dy must be 0..1000')
    args.out.mkdir(parents=True, exist_ok=True)

    def call(command, **values):
        reply = exchange({'id': 'mining-benchmark', 'command': command, **values}, args.port, 20)
        if not reply['ok']:
            raise RuntimeError(reply['error'])
        return reply['result']

    existing = call('macro.status')
    if existing['state'] == 'running':
        raise RuntimeError('Another macro is running; stop it before starting this test')
    initial = call('observe')
    if not initial['active'] or initial['player']['game_mode'] != 'survival':
        raise RuntimeError('Enter survival gameplay before starting')
    if initial['main_hand']['id'] != 'minecraft:netherite_pickaxe' or initial['main_hand']['remaining_durability'] <= 32:
        raise RuntimeError('Hold a netherite pickaxe with more than 32 durability')
    if initial['player']['health'] < 16 or initial['player']['food'] < 4:
        raise RuntimeError('Need health >=16 and food >=4')
    status = call('status')
    if abs(status['pitch']) > 1:
        raise RuntimeError('Start with pitch 0, facing a two-block-high mining wall')
    inventory_before = call('inventory')
    macro = mining_macro(status['tick'] + math.ceil(args.seconds * 20) + 40, args.pitch_dy)
    (args.out / 'macro.json').write_text(json.dumps(macro, indent=2) + '\n')
    (args.out / 'before.json').write_text(json.dumps({'observe': initial, 'inventory': inventory_before}, indent=2) + '\n')
    started = time.monotonic()
    end = started + args.seconds
    samples = []
    reason = 'duration_reached'
    failure = None
    mining_seconds = 0
    try:
        call('macro.run', script=macro)
        print(f'Mining for {args.seconds:g}s; report directory: {args.out}', flush=True)
        while True:
            remaining = end - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(1, remaining))
            if time.monotonic() >= end:
                break
            state = call('macro.status')
            position = call('position')
            samples.append({'elapsed_seconds': time.monotonic() - started, 'position': position, 'macro': state})
            if state['state'] != 'running':
                reason = 'macro_' + state['state']
                break
            if len(samples) % 30 == 0:
                print(f'{samples[-1]["elapsed_seconds"]:.0f}s: ({position["x"]:.1f}, {position["y"]:.1f}, {position["z"]:.1f})', flush=True)
    except BaseException as error:
        failure = repr(error)
        reason = 'interrupted_or_error'
    finally:
        mining_seconds = time.monotonic() - started
        try:
            call('macro.stop')
        except Exception as stop_error:
            failure = f'{failure or ""}; stop error: {stop_error!r}'
        # Persist partial timing even if the world disappears and final snapshots fail.
        report = {'requested_seconds': args.seconds, 'mining_seconds': mining_seconds, 'reason': reason,
                  'error': failure, 'initial': initial, 'inventory_before': inventory_before, 'samples': samples,
                  'note': 'Client inventory net change; excludes uncollected/destroyed drops. Not a natural ore yield estimate.'}
        try:
            # Stop inputs first; allow nearby drops to arrive for a fixed 2 seconds.
            time.sleep(2)
            after = call('observe')
            inventory_after = call('inventory')
            before_counts = Counter(inventory_before['counts'])
            after_counts = Counter(inventory_after['counts'])
            delta = {key: after_counts[key] - before_counts[key] for key in sorted(before_counts.keys() | after_counts.keys())
                     if after_counts[key] != before_counts[key]}
            report.update(final=after, inventory_after=inventory_after, net_items=delta,
                          collected_items=sum(max(0, count) for count in delta.values()))
            if after['main_hand'] and after['main_hand']['id'] == 'minecraft:netherite_pickaxe':
                report['durability_used'] = after['main_hand']['damage'] - initial['main_hand']['damage']
            if after['player']:
                report['displacement'] = math.dist([initial['player'][key] for key in ('x','y','z')],
                                                   [after['player'][key] for key in ('x','y','z')])
        except Exception as snapshot_error:
            report['snapshot_error'] = repr(snapshot_error)
        (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({key: value for key, value in report.items() if key in
                          ('reason','mining_seconds','error','net_items','collected_items','durability_used','displacement','snapshot_error')}, ensure_ascii=False, indent=2), flush=True)
    return 0 if not failure and reason == 'duration_reached' else 1


if __name__ == '__main__':
    raise SystemExit(main())
