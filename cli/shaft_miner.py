#!/usr/bin/env python3
"""Alternate two visible shaft cells down to a target Y, then mine horizontally.

Requires an existing survival player and netherite pickaxe. No world queries,
chat commands, teleports, game-mode changes or item grants are available.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import time
from straight_miner import StraightMiner, SOLID, FALLING, add, at_destination, Unsafe

HEADINGS = {'south':(0,0,1),'west':(-1,0,0),'north':(0,0,-1),'east':(1,0,0)}


class ShaftMiner(StraightMiner):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        self.commands=[]

    def call(self,command,**args):
        # StraightMiner's whitelist also rejects all terrain/administration calls.
        self.commands.append({'command':command,**args})
        return super().call(command,**args)

    def lower_step(self, destination):
        """Open the OTHER column and inspect its landing before leaving support."""
        p=self.call('position')
        origin=tuple(math.floor(p[k]+1e-5) for k in ('x','y','z'))
        if destination[1] != origin[1]-1 or abs(destination[0]-origin[0])+abs(destination[2]-origin[2]) != 1:
            raise Unsafe('Descent requires the adjacent shaft cell exactly one level lower')
        if not self.call('status')['on_ground']:
            raise Unsafe('Descent requires grounded support')
        support=add(origin,(0,-1,0))
        hit=self.look((support[0]+.5,support[1]+.99,support[2]+.5))
        if not hit or hit[0] != support or hit[1] not in SOLID:
            raise Unsafe('Current shaft support is not visibly stable')
        # Clear swept headroom, standing space, then the floor of the OTHER cell.
        cells=[add(destination,(0,2,0)),add(destination,(0,1,0)),destination]
        for cell in cells:
            if cell == support:raise Unsafe('Refuse to mine the current support')
            for _ in range(16):
                self.clear(cell,allow_falling=True)
                time.sleep(.35)
                hit=self.look(tuple(v+.5 for v in cell))
                if not hit or hit[0] != cell:break
            else:raise Unsafe('Falling shaft material did not settle')
        landing=add(destination,(0,-1,0))
        hit=self.look((landing[0]+.5,landing[1]+.99,landing[2]+.5))
        if not hit or hit[0] != landing or hit[1] not in SOLID:
            raise Unsafe(f'No visible solid one-level landing at {landing}; refuse blind drop')
        started=time.monotonic()
        try:
            while True:
                self.state();p=self.call('position')
                if at_destination(p,destination) and self.call('status')['on_ground']:break
                if p['y'] < destination[1]-.2 or p['y'] > origin[1]+.2:
                    raise Unsafe('Unexpected shaft fall height')
                if time.monotonic()-started>5:raise Unsafe('Descent movement stalled')
                self.aim((destination[0]+.5,p['y']+1.62,destination[2]+.5))
                distance=math.hypot(p['x']-destination[0]-.5,p['z']-destination[2]-.5)
                self.call('key.hold',key='w',ticks=1 if distance<.45 else 2)
                time.sleep(.15);self.call('key.up',key='w')
        finally:self.call('key.up',key='w')
        self.events.append({'descended_to':destination,'support_preserved':support})

    def descend(self,target_y,heading,max_seconds):
        self.out.mkdir(parents=True,exist_ok=True)
        if self.call('macro.status')['state']=='running':raise Unsafe('Another macro is running')
        initial=self.state();inventory=self.call('inventory');p=self.call('position')
        origin=tuple(math.floor(p[k]+1e-5) for k in ('x','y','z'))
        if target_y>=origin[1]:raise Unsafe('Target Y must be lower than current feet Y')
        other=add(origin,HEADINGS[heading]);columns=[(origin[0],origin[2]),(other[0],other[2])]
        self.started=time.monotonic();self.deadline=self.started+max_seconds
        failure=None;levels=0;current=origin
        try:
            while current[1]>target_y:
                if time.monotonic()>=self.deadline:raise Unsafe('Descent time limit reached')
                x,z=columns[(levels+1)%2]
                destination=(x,current[1]-1,z)
                self.lower_step(destination);levels+=1;current=destination
                self.save_descent_actions()
                print(f'{time.monotonic()-self.started:.1f}s: shaft Y={current[1]} ({levels} levels)',flush=True)
        except (Exception,KeyboardInterrupt) as error:failure=str(error)
        finally:
            self.call('input.reset');time.sleep(.3)
            final=self.call('observe');after=self.call('inventory');status=self.call('status')
            a=Counter(inventory['counts']);b=Counter(after['counts'])
            report={'target_y':target_y,'origin':origin,'columns':columns,'levels':levels,
                    'elapsed_seconds':time.monotonic()-self.started,'reason':'stopped' if failure else 'target_reached',
                    'error':failure,'initial':initial,'final':final,'inventory_before':inventory,'inventory_after':after,
                    'net_items':{k:b[k]-a[k] for k in sorted(a.keys()|b.keys()) if a[k]!=b[k]},
                    'inputs_released':not status['held_keys'] and not status['held_buttons'],
                    'rules':'alternate two columns; never mine occupied support; only first visible hit; prove solid landing one level down; no region or server commands'}
            self.save_descent_actions()
            (self.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            if failure and status['screen'] is None:self.call('key.tap',key='escape')
            print(json.dumps({k:report[k] for k in ('reason','error','levels','elapsed_seconds')},ensure_ascii=False),flush=True)
        return failure is None

    def save_descent_actions(self):
        (self.out/'actions.json').write_text(json.dumps(self.events,indent=2)+'\n')
        (self.out/'commands.json').write_text(json.dumps(self.commands,indent=2)+'\n')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,default=25580)
    p.add_argument('--target-y',type=int,default=-54)
    p.add_argument('--heading',choices=HEADINGS,default='south')
    p.add_argument('--mine-seconds',type=float,default=600)
    p.add_argument('--pattern',choices=['straight','fishbone'],default='straight')
    p.add_argument('--main-length',type=int,default=64)
    p.add_argument('--branch-length',type=int,default=12)
    p.add_argument('--spacing',type=int,default=3)
    p.add_argument('--descent-timeout',type=float,default=1800)
    p.add_argument('--out',type=Path,default=Path('shaft-results'))
    p.add_argument('--degrees-per-mouse',type=float,default=.15)
    a=p.parse_args()
    if not -60<=a.target_y<=300:p.error('target-y must be -60..300')
    for name in ('mine_seconds','descent_timeout'):
        value=getattr(a,name)
        if not math.isfinite(value) or not 1<=value<=3600:p.error(name+' must be 1..3600')
    if not math.isfinite(a.degrees_per_mouse) or not 0<a.degrees_per_mouse<=10:p.error('invalid mouse scale')
    if not 1<=a.main_length<=256 or not 1<=a.spacing<=64 or not 1<=a.branch_length<=32:p.error('invalid fishbone dimensions')
    miner=ShaftMiner(a.port,a.descent_timeout,a.out/'descent',a.degrees_per_mouse)
    if not miner.descend(a.target_y,a.heading,a.descent_timeout):return 1
    pos=miner.call('position');d=HEADINGS[a.heading]
    miner.aim((pos['x']+d[0]*5,pos['y']+1.62,pos['z']+d[2]*5))
    if a.pattern=='fishbone':
        from fishbone_miner import FishboneMiner
        return FishboneMiner(a.port,a.mine_seconds,a.out/'mining',a.degrees_per_mouse,
                             heading=a.heading,main_length=a.main_length,spacing=a.spacing,branch_length=a.branch_length).run()
    return StraightMiner(a.port,a.mine_seconds,a.out/'mining',a.degrees_per_mouse).run()

if __name__=='__main__':raise SystemExit(main())
