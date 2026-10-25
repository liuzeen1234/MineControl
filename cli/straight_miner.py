#!/usr/bin/env python3
"""Straight survival tunnel; decisions use position and first-hit crosshair only."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import time
from vein_miner import Miner, Unsafe, ROCK, ORES, FALLING, add, at_destination


class StraightMiner(Miner):
    def call(self, command, **args):
        # Deliberately fail closed if inherited or future code attempts a terrain query.
        if command not in {'observe', 'position', 'inventory', 'status', 'macro.status',
                           'mouse.move', 'mouse.hold', 'mouse.up', 'key.hold', 'key.up',
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

    def clear(self, point):
        """Only break the requested cell if it is the first visible hit."""
        hit = self.look(tuple(v+.5 for v in point))
        if hit is None or hit[0] != point:
            # A nearer occluder is not evidence that the requested cell is empty.
            p = self.call('position')
            eye = (p['x'], p['y']+1.62, p['z'])
            if hit and math.dist(eye, tuple(v+.5 for v in hit[0])) < math.dist(eye, tuple(v+.5 for v in point)):
                raise Unsafe(f'Occluded tunnel cell: {point}')
            return
        if hit[1] not in ROCK | ORES.keys():
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

    def step(self, destination):
        self.clear(add(destination,(0,1,0)))
        self.clear(destination)
        floor = add(destination,(0,-1,0))
        hit = self.look((floor[0]+.5, floor[1]+.99, floor[2]+.5))
        if not hit or hit[0] != floor or hit[1] not in ROCK | ORES.keys() | {'minecraft:bedrock'}:
            raise Unsafe(f'No visible solid landing floor: {destination}')
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
