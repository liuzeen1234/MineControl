"""Run only against run-block-test and its explicitly enabled client probe."""
import json, pathlib, time, uuid
root=pathlib.Path('run-block-test/config')
checks=[]
def probe(action):
    ident=str(uuid.uuid4())
    (root/'block-test.request.json').write_text(json.dumps(dict(id=ident,action=action)))
    end=time.monotonic()+20
    while time.monotonic()<end:
        try:
            result=json.loads((root/'block-test.response.json').read_text())
            if result.get('id')==ident:
                assert result['ok'],result
                return result
        except (FileNotFoundError,json.JSONDecodeError): pass
        time.sleep(.1)
    raise AssertionError('Probe timeout '+action)
def check(value,label):
    assert value,label
    checks.append(label);print('PASS',label,flush=True)
probe('stop');time.sleep(4)
probe('setup');check(True,'Client rejects upper iron door, special rails and trial spawner')
time.sleep(3)
probe('start')
def records():
    paths=list((root/'minecontrol-programs/block-detections').glob('*.json'))
    return json.loads(paths[0].read_text())['records'] if paths else []
end=time.monotonic()+150
while time.monotonic()<end:
    data=records()
    expected={('spawner',0,100,0),('spawner',0,171,0),('rail',15,100,0),('rail',86,100,0)}
    actual={(p['type'],p['x'],p['y'],p['z']) for p in data}
    if expected<=actual and len({p['type'] for p in data})==9:break
    time.sleep(.5)
check(expected<=actual,'Saved ordinary rails at 71 blocks and spawners at vertical 71 blocks')
check(('spawner',0,170,0) not in actual and ('rail',85,100,0) not in actual,'Exactly 70 blocks suppressed in vertical and horizontal directions')
check(len({p['type'] for p in data})==9,'All nine target types detected')
check(('iron_door',6,100,0) in actual and not any(p['type']=='iron_door' and p['y']==101 for p in data),'Iron door lower-half coordinates only')
time.sleep(20)
data=records()
snapshot=probe('inspect')
check(not snapshot['captured'] and snapshot['state']=='检测宏','Detection macro leaves manual input available')
points={(p['Name'],int(p['X']),int(p['Y']),int(p['Z'])) for p in snapshot['waypoints']}
names={'spawner':'刷怪笼','end_portal_frame':'末地框架','iron_door':'铁门','iron_bars':'铁栏杆','iron_chain':'锁链','rail':'普通铁轨','reinforced_deepslate':'强化深板岩','vault':'试炼宝库','budding_amethyst':'紫水晶母岩'}
check(all(('检测·'+names[p['type']],p['x'],p['y'],p['z']) in points for p in data),'Xaero active waypoint set contains every saved discovery')
probe('stop');time.sleep(4)
original=records();probe('start');time.sleep(15)
reloaded=records()
check(all(p in reloaded for p in original),'Restarted macro preserves all original records')
check(all(a['type']!=b['type'] or a['dimension']!=b['dimension'] or sum((a[k]-b[k])**2 for k in ('x','y','z'))>4900 for i,a in enumerate(reloaded) for b in reloaded[i+1:]),'Restarted macro has no same-type records within 70 blocks')
after=probe('inspect')
keys=[(p['Name'],p['X'],p['Y'],p['Z']) for p in after['waypoints']]
check(len(keys)==len(set(keys)) and all((p['Name'],p['X'],p['Y'],p['Z']) in keys for p in snapshot['waypoints']),'Restarted macro preserves Xaero waypoints without duplicates')
probe('stop')
pathlib.Path('docs/block-detection-test/results.json').write_text(json.dumps(dict(checks=checks,records=original,waypoints=after['waypoints']),ensure_ascii=False,indent=2))
