#!/usr/bin/env python3
"""Fixed fishbone tunnels with one-block shell search and first-hit ore checks."""
import argparse
from collections import Counter, deque
import json
import math
from pathlib import Path
import time
from straight_miner import StraightMiner, ORES, add, Unsafe
from vein_miner import Miner, parse_blueprint, PASSABLE

DIRECTIONS = {'south':(0,0,1),'west':(-1,0,0),'north':(0,0,-1),'east':(1,0,0)}
FACES = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]


def shell(center, height=2):
    """One Chebyshev layer around an already opened 1xheightx1 column."""
    return {add(center,(x,y,z)) for x in (-1,0,1) for y in range(-1,height+1) for z in (-1,0,1)
            if x or z or y not in range(height)}


def route(origin, heading, main_length, spacing, branch_length):
    forward=DIRECTIONS[heading];side=(forward[2],0,-forward[0])
    for n in range(1,main_length+1):
        anchor=add(origin,tuple(n*v for v in forward))
        yield anchor,'main'
        if n%spacing:continue
        for sign,label in [(1,'left'),(-1,'right')]:
            for k in range(1,branch_length+1):
                yield add(anchor,tuple(sign*k*v for v in side)),label
            for k in range(branch_length-1,-1,-1):
                yield add(anchor,tuple(sign*k*v for v in side)),label+'_return'


class FishboneMiner(StraightMiner):
    def __init__(self,*args,heading='south',main_length=64,spacing=3,branch_length=12,**kwargs):
        super().__init__(*args,**kwargs)
        self.heading=heading;self.main_length=main_length;self.spacing=spacing;self.branch_length=branch_length
        self.opened=set();self.search_origins=set();self.passages=set();self.searches=[];self.deferred=set();self.ore_mined=[]
        self.level=None

    def state(self):
        result=super().state()
        if self.level is not None and abs(result['player']['y']-self.level)>.35:
            raise Unsafe('Unexpected height change: stop fishbone mining')
        return result

    def step(self,destination):
        before=len(self.events)
        try:return super().step(destination)
        finally:
            for event in self.events[before:]:
                if 'placed' in event:
                    self.opened.discard(tuple(event['placed']))
                    self.search_origins.discard(tuple(event['placed']))

    def register_passage(self, feet):
        self.passages.add(feet);self.opened.update([feet,add(feet,(0,1,0))])
        self.search_origins.update([feet,add(feet,(0,1,0))])

    def search_blocks(self, center, height=2):
        if height not in (1,2) or any(add(center,(0,y,0)) not in self.search_origins for y in range(height)):
            raise Unsafe('Shell search requires a known opened cell/column')
        low=add(center,(-1,-1,-1));high=add(center,(1,height,1))
        # Only this bounded function may bypass StraightMiner's general query ban.
        result=Miner.call(self,'region.query',**{'from':list(low),'to':list(high)})
        allowed=shell(center,height)
        blocks=parse_blueprint(result['blueprint'])
        closed=[add(center,(0,y,0)) for y in range(height)
                if add(center,(0,y,0)) in blocks and blocks[add(center,(0,y,0))] not in PASSABLE]
        if closed:
            self.opened.difference_update(closed)
            self.search_origins.difference_update(closed)
            return {}
        # Air observed inside this exact shell may expose corner faces. It does
        # not authorize another query centered on that air (no expanding cave scan).
        self.opened.update(q for q in allowed if q not in blocks or blocks[q] in PASSABLE)
        ores={pos:block for pos,block in blocks.items() if pos in allowed and block in ORES}
        self.searches.append({'center':center,'height':height,'from':low,'to':high,'shell_cells':len(allowed)})
        return ores

    def visible_face(self, ore):
        p=self.call('position');eye=(p['x'],p['y']+1.62,p['z'])
        # Ignore diagonal-only candidates until an actually opened face exposes them.
        faces=[tuple(ore[i]+.5+.499*d[i] for i in range(3)) for d in FACES if add(ore,d) in self.opened]
        for point in sorted(faces,key=lambda q:math.dist(q,eye)):
            if math.dist(point,eye)>4.1:continue
            hit=self.look(point)
            if hit and hit[0]==ore and hit[1] in ORES:return point,hit[1]
        return None

    def known_path(self,start,goal):
        queue=deque([start]);parents={start:None}
        while queue:
            p=queue.popleft()
            if p==goal:
                path=[]
                while parents[p] is not None:path.append(p);p=parents[p]
                return path[::-1]
            for d in [(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)]:
                q=add(p,d)
                if q in self.passages and q not in parents:parents[q]=p;queue.append(q)
        return None

    def local_ores(self, center):
        pending=self.search_blocks(center);visited={center};ore_cells=set();mined=0
        try:
            while mined<128:
                self.state()
                if time.monotonic()>=self.deadline:break
                foot=self.foot();support=add(foot,(0,-1,0));found=None
                for ore in sorted(pending,key=lambda q:(math.dist(q,foot),q)):
                    if ore==support:continue
                    visible=self.visible_face(ore)
                    if visible:found=(ore,visible);break
                if found:
                    ore,(face,block)=found;before=len(self.events)
                    self.clear(ore,aim_point=face)
                    pending.pop(ore,None)
                    if not any(e.get('mined')==ore for e in self.events[before:]):continue
                    self.opened.add(ore);self.search_origins.add(ore)
                    self.ore_mined.append({'pos':ore,'id':block});mined+=1
                    if ore[1]==center[1]:ore_cells.add(ore)
                    pending.update(self.search_blocks(ore,height=1))
                    continue
                # Follow only cavities made by actually mined, visible foot-level ore.
                # This never clears a route toward an occluded or merely queried ore.
                possible=[]
                for q in ore_cells-visited:
                    if math.dist(q,center)>8:continue
                    for d in [(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)]:
                        near=add(q,d);path=self.known_path(foot,near)
                        if path is not None:possible.append((len(path),q,path))
                if not possible:break
                _,q,path=min(possible)
                for step in path:self.step(step)
                self.step(q);self.register_passage(q);visited.add(q)
                pending.update(self.search_blocks(q))
            self.deferred.update(pending)
            if mined>=128:raise Unsafe('Ore work limit reached; vein completion unverified')
        finally:
            # Return to the planned fishbone waypoint along passages we actually opened.
            current=self.foot();back=self.known_path(current,center)
            if back is None:raise Unsafe('No known return path from ore cavity')
            for step in back:self.step(step)

    def run(self):
        self.out.mkdir(parents=True,exist_ok=True)
        if self.call('macro.status')['state']=='running':raise Unsafe('Another macro is running')
        initial=self.state();before=self.call('inventory');origin=self.foot();self.level=origin[1]
        self.register_passage(origin);self.started=time.monotonic();self.deadline=self.started+self.seconds
        reason='route_completed';failure=None;waypoints=[]
        try:
            self.local_ores(origin)
            for destination,phase in route(origin,self.heading,self.main_length,self.spacing,self.branch_length):
                if time.monotonic()>=self.deadline:reason='duration_reached';break
                self.state();self.step(destination)
                fresh=destination not in self.passages;self.register_passage(destination)
                waypoints.append({'pos':destination,'phase':phase})
                if fresh:self.local_ores(destination)
                print(f'{time.monotonic()-self.started:.1f}s: {phase} {destination}, ores={len(self.ore_mined)}',flush=True)
        except (Exception,KeyboardInterrupt) as error:reason='stopped';failure=str(error)
        finally:
            self.call('input.reset');time.sleep(.5)
            after=self.call('inventory');final=self.call('observe');status=self.call('status')
            a=Counter(before['counts']);b=Counter(after['counts'])
            report={'requested_seconds':self.seconds,'elapsed_seconds':time.monotonic()-self.started,
                    'reason':reason,'error':failure,'origin':origin,'heading':self.heading,
                    'main_length':self.main_length,'spacing':self.spacing,'branch_length':self.branch_length,
                    'waypoints':waypoints,'mined_ores':self.ore_mined,'deferred_candidates':sorted(self.deferred-set(tuple(e['pos']) for e in self.ore_mined)),
                    'searches':self.searches,'initial':initial,'final':final,'inventory_before':before,'inventory_after':after,
                    'net_items':{k:b[k]-a[k] for k in sorted(a.keys()|b.keys()) if a[k]!=b[k]},
                    'inputs_released':not status['held_keys'] and not status['held_buttons'],
                    'policy':'fixed orthogonal fishbone; 1-block shell only around opened passages/ore cavities; exposed face plus first-hit verification; no hidden-ore route planning; no complete-vein claim'}
            (self.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            (self.out/'actions.json').write_text(json.dumps(self.events,indent=2)+'\n')
            if status['screen'] is None:self.call('key.tap',key='escape')
            print(json.dumps({k:report[k] for k in ('elapsed_seconds','reason','error','net_items')},ensure_ascii=False),flush=True)
        return 1 if failure else 0


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,default=25580);p.add_argument('--seconds',type=float,default=600)
    p.add_argument('--heading',choices=DIRECTIONS,default='south')
    p.add_argument('--main-length',type=int,default=64);p.add_argument('--spacing',type=int,default=3)
    p.add_argument('--branch-length',type=int,default=12);p.add_argument('--out',type=Path,default=Path('fishbone-results'))
    p.add_argument('--degrees-per-mouse',type=float,default=.15)
    a=p.parse_args()
    if not math.isfinite(a.seconds) or not 1<=a.seconds<=3600:p.error('seconds must be 1..3600')
    if not 1<=a.main_length<=256 or not 1<=a.spacing<=64 or not 1<=a.branch_length<=32:p.error('invalid route dimensions')
    if not math.isfinite(a.degrees_per_mouse) or not 0<a.degrees_per_mouse<=10:p.error('invalid mouse scale')
    return FishboneMiner(a.port,a.seconds,a.out,a.degrees_per_mouse,heading=a.heading,main_length=a.main_length,spacing=a.spacing,branch_length=a.branch_length).run()

if __name__=='__main__':raise SystemExit(main())
