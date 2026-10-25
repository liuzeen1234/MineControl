#!/usr/bin/env python3
"""Straight survival tunnel; decisions use position and first-hit crosshair only."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import time
from vein_miner import Miner, Unsafe, ROCK, ORES, FALLING, PLANTS, TORCHES, add, at_destination

BUILDING = ROCK - {'minecraft:moss_block', 'minecraft:dripstone_block'}
SOLID = ROCK | ORES.keys() | {'minecraft:bedrock'}
CLEARABLE = ROCK | ORES.keys() | PLANTS | TORCHES | {'minecraft:lily_pad'}


class StraightMiner(Miner):
    def call(self, command, **args):
        # Deliberately fail closed if inherited or future code attempts a terrain query.
        if command not in {'observe', 'position', 'inventory', 'status', 'macro.status',
                           'mouse.move', 'mouse.hold', 'mouse.click', 'mouse.up', 'key.hold', 'key.up',
                           'key.tap', 'input.reset'}:
            raise Unsafe(f'Command forbidden by visible-only policy: {command}')
        return super().call(command, **args)

    def look(self, point):
        self.state()
        self.aim(point)
        time.sleep(.10)
        target = self.call('observe')['target']
        if target and target['kind'] == 'block':
            pos = tuple(target['pos'][k] for k in ('x', 'y', 'z'))
            self.events.append({'seen': pos, 'id': target['id']})
            return pos, target['id']
        return None

    def clear(self, point, allow_falling=False):
        """Only break the requested cell if it is the first visible hit."""
        hit = self.look(tuple(v+.5 for v in point))
        if hit is None or hit[0] != point:
            # A nearer occluder is not evidence that the requested cell is empty.
            p = self.call('position')
            eye = (p['x'], p['y']+1.62, p['z'])
            if hit and math.dist(eye, tuple(v+.5 for v in hit[0])) < math.dist(eye, tuple(v+.5 for v in point)):
                raise Unsafe(f'Occluded tunnel cell: {point}')
            return
        wood_or_leaves = hit[1].startswith('minecraft:') and hit[1].endswith(('_log','_wood','_leaves'))
        if hit[1] not in (CLEARABLE | (FALLING if allow_falling else set())) and not wood_or_leaves:
            raise Unsafe(f'Unsupported/falling visible block: {hit}')
        started = time.monotonic()
        try:
            self.call('mouse.hold', button='left', ticks=180)
            while True:
                time.sleep(.10)
                self.state()
                target = self.call('observe')['target']
                if not target or target['kind'] != 'block' or tuple(target['pos'][k] for k in ('x','y','z')) != point:
                    self.events.append({'mined': point, 'id': hit[1]})
                    return
                if target['id'] != hit[1]:
                    raise Unsafe('Visible block changed during mining; recheck required')
                if time.monotonic()-started > 8:
                    raise Unsafe(f'Block did not break: {point}')
        finally:
            self.call('mouse.up', button='left')

    def exposed_ores(self, center):
        # Fixed local surface probes. A ray may hit an occluder; never mine it to seek ore.
        # No queue of hidden coordinates or prediction of vein size.
        for _ in range(32):
            found = None
            for dx,dy,dz in [(1,0,0),(-1,0,0),(0,0,1),(0,0,-1),
                             (1,1,0),(-1,1,0),(0,1,1),(0,1,-1),(0,2,0)]:
                point = add(center,(dx,dy,dz))
                hit = self.look(tuple(v+.5 for v in point))
                if hit and hit[1] in ORES and hit[0][1] >= center[1] and hit[0] != add(center,(0,-1,0)):
                    found = hit[0]
                    break
            if found is None:
                return
            self.clear(found)
        raise Unsafe('Visible ore work limit reached')

    def bridge(self, destination):
        """Sneak to the edge, place against our visible support, verify before walking."""
        p = self.call('position')
        origin = tuple(math.floor(p[k]) for k in ('x','y','z'))
        direction = tuple(destination[i]-origin[i] for i in range(3))
        if direction not in {(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)}:
            raise Unsafe('Bridge requires an adjacent level destination')
        support = add(origin,(0,-1,0))
        hit = self.look(tuple(v+.5 for v in support))
        if not hit or hit[0] != support or hit[1] not in SOLID:
            raise Unsafe('No visible stable bridge anchor')
        slots = self.call('inventory')['slots']
        blocks = [s for s in slots if 0 <= s['slot'] < 9 and s['stack']['id'] in BUILDING and s['stack']['count'] > 0]
        tools = [s for s in slots if 0 <= s['slot'] < 9 and s['stack']['id'] == 'minecraft:netherite_pickaxe']
        if not blocks or not tools:
            raise Unsafe('Bridge needs building blocks and netherite pickaxe in hotbar')
        material = blocks[0]; tool = tools[0]
        floor = add(destination,(0,-1,0))
        expected_face = {(1,0,0):'east',(-1,0,0):'west',(0,0,1):'south',(0,0,-1):'north'}[direction]
        started = time.monotonic()
        try:
            self.call('key.hold',key='left_shift',ticks=200)
            while True:
                self.state();p = self.call('position')
                advance = sum((p[k]-origin[i]-.5)*direction[i] for i,k in enumerate(('x','y','z')))
                if abs(p['y']-origin[1]) > .2:
                    raise Unsafe('Bridge edge height changed')
                if advance >= .65:break
                if time.monotonic()-started > 5:raise Unsafe('Could not reach bridge edge while sneaking')
                self.aim((destination[0]+.5,p['y']+1.62,destination[2]+.5))
                self.call('key.hold',key='w',ticks=2);time.sleep(.15);self.call('key.up',key='w')
            # Sneaking eye height is 0.35 lower than the standing-eye aim helper.
            point = tuple(support[i]+.5+direction[i]*.499 for i in range(3))
            self.aim((point[0],point[1]+.35,point[2]));time.sleep(.15)
            target = self.call('observe')['target']
            if not target or target['kind'] != 'block' or tuple(target['pos'][k] for k in ('x','y','z')) != support or target['face'] != expected_face:
                raise Unsafe('Bridge anchor side is not visible')
            self.call('key.tap',key=str(material['slot']+1));time.sleep(.15)
            self.call('mouse.click',button='right');time.sleep(.25)
            self.call('mouse.up',button='right')
            self.call('key.tap',key=str(tool['slot']+1));time.sleep(.15)
            # Keep sneaking while checking the newly placed block's top.
            self.aim((floor[0]+.5,floor[1]+.99+.35,floor[2]+.5));time.sleep(.15)
            target = self.call('observe')['target']
            if not target or target['kind'] != 'block' or tuple(target['pos'][k] for k in ('x','y','z')) != floor or target['id'] != material['stack']['id']:
                raise Unsafe('Bridge placement not confirmed')
            self.events.append({'placed':floor,'id':target['id']})
        finally:
            self.call('mouse.up',button='right')
            self.call('key.up',key='w')
            self.call('key.tap',key=str(tool['slot']+1))
            self.call('key.up',key='left_shift')

    def prepare_floor(self, destination):
        floor = add(destination,(0,-1,0))
        hit = self.look((floor[0]+.5, floor[1]+.99, floor[2]+.5))
        attempts = 0
        while hit and hit[0] == floor and hit[1] in FALLING:
            if attempts >= 16:raise Unsafe('Falling floor did not clear within work limit')
            self.clear(floor,allow_falling=True)
            time.sleep(.5)
            hit = self.look((floor[0]+.5,floor[1]+.99,floor[2]+.5))
            attempts += 1
        if hit and hit[0] == floor and hit[1] in SOLID:return
        if hit and hit[0] == floor:
            raise Unsafe(f'Unsupported visible landing floor: {hit}')
        self.bridge(destination)

    def step(self, destination):
        # Both cells are ahead of us: clear falling material from the side.
        cells = [add(destination,(0,1,0)),destination]
        for _ in range(16):
            self.clear(cells[0],allow_falling=True)
            time.sleep(.5)
            head = self.look(tuple(v+.5 for v in cells[0]))
            if head and head[0] == cells[0]:continue
            self.clear(cells[1],allow_falling=True)
            time.sleep(.5)
            # Gravel can fall into the cells after the mining ray moves away.
            hits = [self.look(tuple(v+.5 for v in cell)) for cell in cells]
            if all(not hit or hit[0] != cell for cell,hit in zip(cells,hits)):break
        else:raise Unsafe('Falling tunnel blocks did not settle within work limit')
        self.prepare_floor(destination)
        start = time.monotonic()
        try:
            while True:
                self.state()
                p = self.call('position')
                if at_destination(p,destination):
                    break
                if abs(p['y']-destination[1]) > .35 or time.monotonic()-start > 5:
                    raise Unsafe('Unexpected height or stalled movement')
                self.aim((destination[0]+.5,p['y']+1.62,destination[2]+.5))
                distance = math.hypot(p['x']-destination[0]-.5,p['z']-destination[2]-.5)
                self.call('key.hold', key='w', ticks=1 if distance < .5 else 2)
                time.sleep(.15)
                self.call('key.up',key='w')
        finally:
            self.call('key.up',key='w')
        self.events.append({'walked': destination})

    def run(self):
        self.out.mkdir(parents=True,exist_ok=True)
        if self.call('macro.status')['state']=='running':
            raise Unsafe('Another macro is running')
        initial = self.state()
        before = self.call('inventory')
        p = self.call('position')
        origin = tuple(math.floor(p[k]) for k in ('x','y','z'))
        yaw = round(p['yaw']/90)*90
        direction = (round(-math.sin(math.radians(yaw))),0,round(math.cos(math.radians(yaw))))
        self.started=time.monotonic();self.deadline=self.started+self.seconds
        failure=None;steps=0
        try:
            while time.monotonic() < self.deadline:
                center=add(origin,tuple(v*steps for v in direction))
                self.exposed_ores(center)
                destination=add(center,direction)
                self.step(destination);steps+=1
                print(f'{time.monotonic()-self.started:.1f}s: {steps} straight steps',flush=True)
        except (Exception,KeyboardInterrupt) as error:
            failure=str(error)
        finally:
            self.call('input.reset')
            time.sleep(.5)
            after=self.call('inventory');final=self.call('observe');status=self.call('status')
            a=Counter(before['counts']);b=Counter(after['counts'])
            report={'requested_seconds':self.seconds,'elapsed_seconds':time.monotonic()-self.started,
                    'reason':'stopped' if failure else 'duration_reached','error':failure,'steps':steps,
                    'origin':origin,'direction':direction,'initial':initial,'final':final,
                    'inventory_before':before,'inventory_after':after,
                    'net_items':{k:b[k]-a[k] for k in sorted(a.keys()|b.keys()) if a[k]!=b[k]},
                    'durability_used':final['main_hand']['damage']-initial['main_hand']['damage'],
                    'inputs_released':not status['held_keys'] and not status['held_buttons'],
                    'policy':'first-hit observe only; no region queries; fixed straight route; reachable visible ores only; no full-vein completeness claim'}
            (self.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            (self.out/'actions.json').write_text(json.dumps(self.events,indent=2)+'\n')
            if status['screen'] is None:self.call('key.tap',key='escape')
            print(json.dumps({k:report[k] for k in ('elapsed_seconds','reason','error','steps','net_items')},ensure_ascii=False),flush=True)
        return 1 if failure else 0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=25580)
    parser.add_argument('--seconds',type=float,default=600)
    parser.add_argument('--out',type=Path,default=Path('straight-results'))
    parser.add_argument('--degrees-per-mouse',type=float,default=.15)
    args=parser.parse_args()
    if not math.isfinite(args.seconds) or not 1<=args.seconds<=3600:parser.error('seconds must be 1..3600')
    if not math.isfinite(args.degrees_per_mouse) or not 0<args.degrees_per_mouse<=10:parser.error('invalid mouse scale')
    return StraightMiner(args.port,args.seconds,args.out,args.degrees_per_mouse).run()

if __name__=='__main__':raise SystemExit(main())
