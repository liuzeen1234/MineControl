#!/usr/bin/env python3
"""Client-only feedback mining: scan surroundings, trace 26-connected ore, mine and audit."""
import argparse
from collections import Counter, deque
import heapq
import itertools
import json
import math
from pathlib import Path
import re
import time
from minectl import exchange

AIR = 'minecraft:air'
PLANTS = {'minecraft:'+name for name in ('short_grass','tall_grass','fern','large_fern','dead_bush','dandelion','poppy','blue_orchid','allium','azure_bluet','red_tulip','orange_tulip','white_tulip','pink_tulip','oxeye_daisy','cornflower','lily_of_the_valley','torchflower','pink_petals','wildflowers','bush','firefly_bush','leaf_litter','vine','glow_lichen')}
TORCHES = {'minecraft:torch','minecraft:wall_torch'}
PASSABLE = {AIR} | PLANTS | TORCHES
FAMILIES = ('coal', 'iron', 'copper', 'gold', 'redstone', 'lapis', 'diamond', 'emerald')
ORES = {f'minecraft:{prefix}{name}_ore': name for name in FAMILIES for prefix in ('', 'deepslate_')}
ORES.update({'minecraft:raw_iron_block':'iron','minecraft:raw_copper_block':'copper'})
ROCK = {'minecraft:' + name for name in ('stone', 'deepslate', 'granite', 'diorite', 'andesite', 'tuff', 'calcite', 'dirt', 'grass_block', 'cobbled_deepslate', 'cobblestone', 'netherrack', 'sandstone', 'smooth_sandstone', 'cut_sandstone', 'red_sandstone', 'smooth_red_sandstone', 'cut_red_sandstone', 'clay', 'moss_block', 'dripstone_block')}
LIQUIDS = {'minecraft:lava', 'minecraft:water'}
FALLING = {'minecraft:gravel', 'minecraft:sand', 'minecraft:red_sand'}
DIG = ROCK | ORES.keys() | FALLING | PLANTS | TORCHES
FLOOR = (ROCK | ORES.keys()) | {'minecraft:bedrock'}
HAZARDS = LIQUIDS | FALLING
OFFSETS = [p for p in itertools.product((-1, 0, 1), repeat=3) if p != (0, 0, 0)]
LINE = re.compile(r'^(-?\d+),(-?\d+),(-?\d+)\s+(\S+)')


def add(a, b): return tuple(x+y for x,y in zip(a,b))
def at_destination(position, target):
    return math.hypot(position['x']-target[0]-.5,position['z']-target[2]-.5)<.24 and abs(position['y']-target[1])<.15

def parse_blueprint(text):
    result = {}
    for line in text.splitlines():
        match = LINE.match(line)
        if match: result[tuple(map(int, match.group(1,2,3)))] = match[4]
    return result


class Unsafe(RuntimeError): pass


class Terrain:
    def __init__(self): self.blocks = {}
    def update(self, low, high, blocks):
        for x in range(low[0], high[0]+1):
            for y in range(low[1], high[1]+1):
                for z in range(low[2], high[2]+1): self.blocks[x,y,z] = blocks.get((x,y,z), AIR)
    def get(self, pos): return self.blocks.get(pos)
    def liquid_or_unknown(self, pos):
        neighbors=[self.get(add(pos,d)) for d in [(0,0,0)] + OFFSETS]
        return any(block is None or block in LIQUIDS for block in neighbors)
    def hazard(self,pos):
        return self.liquid_or_unknown(pos) or self.get(add(pos,(0,1,0))) in FALLING
    def standable(self, pos):
        feet, head = self.get(pos), self.get(add(pos,(0,1,0)))
        return (self.get(add(pos,(0,-1,0))) in FLOOR and feet in DIG | PASSABLE and head in DIG | PASSABLE
                and not self.hazard(pos) and not self.hazard(add(pos,(0,1,0))))
    def can_step(self,origin,destination):
        if not self.standable(destination):return False
        if destination[1]>origin[1] and self.get(add(origin,(0,2,0))) not in DIG | PASSABLE:return False
        return all(self.get((destination[0],y,destination[2])) in DIG | PASSABLE
                   for y in range(destination[1],max(origin[1],destination[1])+2))
    def route(self, start, target, max_nodes=12000, pickup=False):
        def goal(pos):
            eye = (pos[0]+.5,pos[1]+1.62,pos[2]+.5)
            distance = math.dist(eye, tuple(v+.5 for v in target))
            if pickup: return pos == target
            if distance > 4.1 or target == add(pos,(0,-1,0)): return False
            endpoint=tuple(v+.5 for v in target)
            # Do not select a shooting position whose ray needs mining its own support.
            for i in range(1,41):
                sample=tuple(math.floor(eye[j]+(endpoint[j]-eye[j])*i/40) for j in range(3))
                if sample == add(pos,(0,-1,0)): return False
                if sample not in (pos,add(pos,(0,1,0))) and self.get(sample) not in DIG | PASSABLE: return False
            return True
        queue = [(0,start)]; costs={start:0}; parent={}
        for _ in range(max_nodes):
            if not queue: return None
            cost,pos=heapq.heappop(queue)
            if cost != costs[pos]: continue
            if goal(pos):
                path=[]
                while pos != start: path.append(pos);pos=parent[pos]
                return path[::-1]
            for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
                for dy in (0,1,-1):
                    nxt=add(pos,(dx,dy,dz))
                    if not self.can_step(pos,nxt): continue
                    if dy==1 and self.get(add(pos,(0,2,0))) not in DIG | PASSABLE: continue
                    dig=sum(self.get(p) != AIR for p in [nxt,add(nxt,(0,1,0))])
                    new=cost+1+dig*3+abs(dy)
                    if new < costs.get(nxt,math.inf): costs[nxt]=new;parent[nxt]=pos;heapq.heappush(queue,(new,nxt))
        return None


class Miner:
    def __init__(self, port, seconds, output, degrees_per_mouse, max_veins=None, resume=None):
        self.port=port;self.seconds=seconds;self.out=output;self.factor=degrees_per_mouse;self.max_veins=max_veins;self.resume=resume
        self.terrain=Terrain();self.events=[];self.veins=[];self.visits=Counter();self.started=None;self.deadline=None
    def call(self, command, **args):
        response=exchange({'id':'vein-miner','command':command,**args}, self.port, 20)
        if not response['ok']: raise Unsafe(str(response['error']))
        return response['result']
    def state(self):
        obs=self.call('observe')
        if not obs['active'] or not obs['player'] or not obs['player']['alive']: raise Unsafe('World/UI/player unavailable')
        if obs['player']['game_mode']!='survival': raise Unsafe('Not in survival')
        if obs['player']['health']<16 or obs['player']['food']<6: raise Unsafe('Health or hunger protection')
        if obs['main_hand']['id']!='minecraft:netherite_pickaxe' or obs['main_hand']['remaining_durability']<=40: raise Unsafe('Tool protection')
        if self.deadline and time.monotonic()>self.deadline+180: raise Unsafe('Current-vein finish allowance exhausted')
        return obs
    def foot(self):
        p=self.call('position');return tuple(math.floor(p[k]+1e-5) for k in ('x','y','z'))
    def scan(self, low, high):
        r=self.call('region.query',**{'from':list(low),'to':list(high)})
        blocks=parse_blueprint(r['blueprint']);self.terrain.update(low,high,blocks)
        return blocks
    def nearby(self, radius=7):
        center=self.foot();self.scan(add(center,(-radius,-5,-radius)),add(center,(radius,7,radius)))
        return center
    def aim(self, point):
        p=self.call('position');dx=point[0]-p['x'];dy=point[1]-(p['y']+1.62);dz=point[2]-p['z']
        yaw=math.degrees(math.atan2(-dx,dz));pitch=-math.degrees(math.atan2(dy,math.hypot(dx,dz)))
        turn=(yaw-p['yaw']+180)%360-180
        self.call('mouse.move',dx=turn/self.factor,dy=(pitch-p['pitch'])/self.factor)
    def mine(self, target, depth=0):
        self.state()
        if depth>8: raise Unsafe('Too many line-of-sight obstructions')
        self.scan(target,target)
        if self.terrain.get(target)==AIR: return
        if self.terrain.get(target) not in DIG: raise Unsafe(f'Unsupported block {target}: {self.terrain.get(target)}')
        if self.terrain.liquid_or_unknown(target): raise Unsafe(f'Water/lava or unknown boundary near {target}')
        above=add(target,(0,1,0))
        if self.terrain.get(above) in FALLING:
            foot=self.foot()
            if (foot[0],foot[2]) == (target[0],target[2]): raise Unsafe('Refuse to clear falling column from underneath')
            self.mine(above,depth+1)
            self.scan(add(target,(-1,-1,-1)),add(target,(1,1,1)))
            if self.terrain.get(above) in FALLING: raise Unsafe('Falling column did not clear')
        if target==add(self.foot(),(0,-1,0)): raise Unsafe('Refuse to dig current support')
        self.aim(tuple(v+.5 for v in target));time.sleep(.06)
        hit=self.call('observe')['target']
        if not hit or hit['kind']!='block': raise Unsafe(f'Cannot ray-target {target}')
        actual=tuple(hit['pos'][k] for k in ('x','y','z'))
        if actual!=target:
            self.mine(actual,depth+1);return self.mine(target,depth+1)
        started=time.monotonic();old=self.terrain.get(target)
        try:
            self.call('mouse.hold',button='left',ticks=200)
            while True:
                time.sleep(.16);self.scan(target,target)
                if self.terrain.get(target)!=old: break
                self.state()
                if time.monotonic()-started>8: raise Unsafe(f'Block not breaking: {target}')
        finally: self.call('mouse.up',button='left')
        self.events.append({'t':time.monotonic()-self.started,'mined':target,'id':old})
    def walk(self, destination):
        origin=self.foot()
        if self.terrain.get(add(destination,(0,-1,0))) not in FLOOR: raise Unsafe('No known solid landing support')
        if destination[1]>origin[1]: self.mine_if_needed(add(origin,(0,2,0)))
        for y in range(max(origin[1],destination[1])+1,destination[1]-1,-1):
            self.mine_if_needed((destination[0],y,destination[2]))
        started=time.monotonic()
        try:
            while True:
                self.state();p=self.call('position')
                distance=math.hypot(p['x']-destination[0]-.5,p['z']-destination[2]-.5)
                if at_destination(p,destination):
                    self.call('key.up',key='w');self.call('key.up',key='space')
                    if self.call('status')['on_ground']: break
                    time.sleep(.1);continue
                if time.monotonic()-started>5: raise Unsafe(f'Cannot walk to {destination}')
                # Horizontal movement faces the destination; mining aim is set separately.
                self.aim((destination[0]+.5,p['y']+1.62,destination[2]+.5))
                self.call('key.up',key='w')
                if destination[1]>p['y']+.2:
                    self.call('key.up',key='space');self.call('key.hold',key='space',ticks=6)
                self.call('key.hold',key='w',ticks=1 if distance<.35 else 2 if distance<.6 else 3);time.sleep(.18)
        finally:
            self.call('key.up',key='w');self.call('key.up',key='space')
        self.events.append({'t':time.monotonic()-self.started,'walked':destination})
    def mine_if_needed(self, point):
        self.scan(point,point)
        if self.terrain.get(point) not in PASSABLE: self.mine(point)
    def torch(self):
        center=self.foot()
        candidates=[add(center,d) for d in [(0,0,0),(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)]]
        for pos in candidates:
            if self.terrain.get(pos)==AIR and self.terrain.get(add(pos,(0,-1,0))) in FLOOR:
                self.aim((pos[0]+.5,pos[1]-.01,pos[2]+.5))
                self.call('key.tap',key='3');time.sleep(.1)
                try:self.call('mouse.click',button='right');time.sleep(.15)
                finally:self.call('key.tap',key='1');time.sleep(.1)
                self.scan(pos,pos)
                self.events.append({'t':time.monotonic()-self.started,'torch_attempt':pos,'placed':self.terrain.get(pos)=='minecraft:torch'})
                return
    def trace(self, seed, removed=None):
        removed=removed or {}
        family=ORES[removed.get(seed,self.terrain.get(seed))];members=set();queue=deque([seed]);examined=set()
        while queue:
            pos=queue.popleft()
            if pos in examined: continue
            examined.add(pos)
            self.scan(add(pos,(-1,-1,-1)),add(pos,(1,1,1)))
            if ORES.get(removed.get(pos,self.terrain.get(pos)))!=family: continue
            members.add(pos)
            if len(members)>512: raise Unsafe('Vein exceeds 512 block trace bound; completeness unknown')
            for offset in OFFSETS:
                neighbor=add(pos,offset)
                if ORES.get(removed.get(neighbor,self.terrain.get(neighbor)))==family and neighbor not in examined: queue.append(neighbor)
        return family,members
    def vein(self, seed, known=None):
        record={'family':ORES.get(self.terrain.get(seed)),'seed':seed,'members':[],'boundary_complete':False,'complete':False,'remaining':[]}
        self.veins.append(record)
        try:
            family,members=known if known is not None else self.trace(seed)
        except Exception as error:
            record['error']=str(error);self.save_partial();raise
        record.update(family=family,members=sorted(members),boundary_complete=True,remaining=sorted(members));self.save_partial()
        print(f'Found {family} vein: {len(members)} blocks',flush=True)
        pending=set(members)
        try:
            while pending:
                self.state();self.nearby()
                # Refresh every member's exact state before choosing the next target.
                remaining=set()
                for ore in pending:
                    self.scan(ore,ore)
                    if ORES.get(self.terrain.get(ore))==family: remaining.add(ore)
                pending=remaining
                if not pending: break
                choices=[];origin=self.foot()
                for target in sorted(pending,key=lambda p:math.dist(p,origin)):
                    route=self.terrain.route(origin,target)
                    if route is not None: choices.append((len(route),target,route))
                if not choices: raise Unsafe('No safe known route to remaining vein blocks')
                _,target,path=min(choices)
                for step in path: self.walk(step)
                self.mine(target);pending.discard(target)
                record['remaining']=sorted(pending);self.save_partial()
            # Scan all original boundaries again: same-family ore touching any member is unfinished.
            residual=set()
            for ore in members:
                blocks=self.scan(add(ore,(-1,-1,-1)),add(ore,(1,1,1)))
                residual.update(pos for pos,block in blocks.items() if ORES.get(block)==family)
            record['remaining']=sorted(residual);record['complete']=not residual
            if residual: raise Unsafe('Post-mine boundary audit still found same-family ore')
            # Approach safe mined cells to collect drops; never manufacture a completeness claim from inventory counts.
            self.nearby()
            landings=set();unreachable=[]
            for ore in members:
                landing=None
                for y in range(ore[1],ore[1]-6,-1):
                    pos=(ore[0],y,ore[2])
                    if (self.terrain.standable(pos) and self.terrain.get(pos) in PASSABLE
                            and self.terrain.get(add(pos,(0,1,0))) in PASSABLE): landing=pos;break
                if landing is not None:landings.add(landing)
                else:unreachable.append(ore)
            for landing in sorted(landings,key=lambda p:math.dist(p,self.foot())):
                path=self.terrain.route(self.foot(),landing,pickup=True)
                if path is not None:
                    for step in path:self.walk(step)
                else:unreachable.append(landing)
            record['collection_unreachable']=unreachable
            print(f'Verified complete {family} vein ({len(members)} blocks)',flush=True)
        except Exception as error:
            record['error']=str(error)
            # A failure is explicitly incomplete until a fresh boundary audit proves otherwise.
            if record['remaining']: record['complete']=False
            raise
        finally: self.save_partial()
    def incidental(self):
        removed={tuple(event['mined']):event['id'] for event in self.events if 'mined' in event and event['id'] in ORES}
        claimed={tuple(p) for record in self.veins for p in record['members']}
        pending=[p for p in removed if p not in claimed]
        if not pending:return None
        seed=pending[0]
        return seed,self.trace(seed,removed=removed)
    def explore_step(self,center):
        self.visits[center]+=1
        candidates=[]
        for dx,dz in [(0,1),(1,0),(-1,0),(0,-1)]:
            for dy in (0,-1,1):
                point=add(center,(dx,dy,dz))
                if self.terrain.can_step(center,point):
                    score=self.visits[point]*10-2*dz+abs(dy)
                    candidates.append((score,point))
        if not candidates:raise Unsafe('No safe known step around natural void or obstruction')
        self.walk(min(candidates)[1])
    def save_partial(self):
        self.out.mkdir(parents=True,exist_ok=True)
        (self.out/'veins.json').write_text(json.dumps(self.veins,indent=2)+'\n')
        (self.out/'actions.json').write_text(json.dumps(self.events,indent=2)+'\n')
    def run(self):
        self.out.mkdir(parents=True,exist_ok=True)
        if self.call('macro.status')['state']=='running': raise Unsafe('Stop existing macro before vein test')
        initial=self.state();inventory=self.call('inventory')
        (self.out/'before.json').write_text(json.dumps({'observe':initial,'inventory':inventory},indent=2)+'\n')
        self.started=time.monotonic();self.deadline=self.started+self.seconds
        reason='duration_reached';failure=None
        last_torch=0
        try:
            self.nearby();self.torch()
            if self.resume:
                records=json.loads(self.resume.read_text())
                if isinstance(records,dict):records=records['veins']
                for old in records:
                    if old.get('boundary_complete') and old.get('members') and (not old.get('complete') or old.get('error')):
                        self.vein(tuple(old['seed']),known=(old['family'],set(map(tuple,old['members']))))
            while True:
                encountered=self.incidental()
                if encountered:
                    seed,known=encountered;self.vein(seed,known=known);continue
                if time.monotonic()>=self.deadline:break
                if self.max_veins is not None and sum(v['complete'] for v in self.veins)>=self.max_veins:
                    reason='vein_limit_reached';break
                self.state();center=self.nearby()
                if len(self.events)-last_torch>=20:
                    self.torch();last_torch=len(self.events)
                candidates=[pos for pos,block in self.terrain.blocks.items() if block in ORES and math.dist(pos,center)<=6]
                if candidates:
                    seed=min(candidates,key=lambda p:math.dist(p,center));self.vein(seed)
                else:
                    self.explore_step(center)
                if len(self.events)%10==0: print(f'{time.monotonic()-self.started:.0f}s, {len(self.events)} actions, {len(self.veins)} veins',flush=True)
        except BaseException as error: reason='stopped_incomplete_or_unsafe';failure=repr(error)
        finally:
            try:self.call('input.reset')
            except Exception:pass
            elapsed=time.monotonic()-self.started;time.sleep(2)
            report={'requested_seconds':self.seconds,'elapsed_seconds':elapsed,'reason':reason,'error':failure,
                    'veins':self.veins,'initial':initial,'inventory_before':inventory,'rules':'26-connected same ore family, including deepslate variant; all 26-neighbor boundaries queried and post-mining audited'}
            try:
                after=self.call('observe');inv_after=self.call('inventory')
                a=Counter(inventory['counts']);b=Counter(inv_after['counts'])
                report.update(final=after,inventory_after=inv_after,net_items={key:b[key]-a[key] for key in sorted(a.keys()|b.keys()) if a[key]!=b[key]})
                report['durability_used']=after['main_hand']['damage']-initial['main_hand']['damage']
                report['inputs_released']=not self.call('status')['held_keys'] and not self.call('status')['held_buttons']
            except Exception as error:report['snapshot_error']=repr(error)
            self.save_partial();(self.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            try:
                if self.call('status')['screen'] is None:self.call('key.tap',key='escape')
            except Exception:pass
            print(json.dumps({key:report[key] for key in ['elapsed_seconds','reason','error','net_items'] if key in report},ensure_ascii=False,indent=2),flush=True)
        return 0 if failure is None else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=25580);parser.add_argument('--seconds',type=float,default=600)
    parser.add_argument('--out',type=Path,default=Path('vein-results'))
    parser.add_argument('--max-veins',type=int,help='stop after this many verified veins, including resumed audits')
    parser.add_argument('--resume',type=Path,help='resume incomplete/collection-failed records from veins.json or report.json')
    parser.add_argument('--degrees-per-mouse',type=float,default=.15,help='default sensitivity 0.5; mouse inversion must be off')
    args=parser.parse_args()
    if not math.isfinite(args.seconds) or not 1<=args.seconds<=3600:parser.error('seconds must be 1..3600')
    if not math.isfinite(args.degrees_per_mouse) or not 0<args.degrees_per_mouse<=10:parser.error('invalid mouse scale')
    if args.max_veins is not None and args.max_veins<1:parser.error('max-veins must be positive')
    return Miner(args.port,args.seconds,args.out,args.degrees_per_mouse,args.max_veins,args.resume).run()

if __name__=='__main__':raise SystemExit(main())
