"""Single-pass live acceptance, only against the isolated run-block-once-test world."""
import json,pathlib,socket,time,uuid
root=pathlib.Path('run-block-once-test/config')
checks=[]
def call(command,token=None,**params):
    request=dict(params,id=str(uuid.uuid4()),command=command)
    if token:request['macro_token']=token
    with socket.create_connection(('127.0.0.1',25587),timeout=5) as s:
        s.settimeout(20);s.sendall((json.dumps(request)+'\n').encode());reply=json.loads(s.makefile().readline())
    assert reply['ok'],reply
    return reply['result']
def probe(action):
    identifier=str(uuid.uuid4());(root/'block-test.request.json').write_text(json.dumps(dict(id=identifier,action=action)))
    until=time.monotonic()+20
    while time.monotonic()<until:
        try:
            result=json.loads((root/'block-test.response.json').read_text())
            if result.get('id')==identifier:
                assert result['ok'],result
                return result
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.1)
    raise AssertionError('Probe timeout '+action)
def check(value,label):
    assert value,label
    checks.append(label);print('PASS',label,flush=True)
def records():
    files=list((root/'minecontrol-programs/block-detections').glob('*.json'))
    return json.loads(files[0].read_text())['records'] if files else []
until=time.monotonic()+60
while time.monotonic()<until:
    try:
        if call('status').get('world_loaded'):break
    except (OSError,AssertionError):pass
    time.sleep(.5)
probe('setup');time.sleep(5)
probe('setup');time.sleep(3)
observer=call('macro.register',role='detection',name='单次检查验收观察者')['macro_token']
events=[]
def run_once():
    call('bus.poll',observer);probe('start');until=time.monotonic()+150
    while time.monotonic()<until:
        messages=call('bus.poll',observer)['messages']
        exits=[m['data'] for m in messages if m['data'].get('event')=='exited' and m['data'].get('name')=='目标方块检测']
        if exits:
            event=exits[0];events.append(event)
            check(len(exits)==1 and event['reason']=='检查完成' and event['exit_type']=='normal','Exactly one normal exit broadcast with reason 检查完成')
            return
        time.sleep(.2)
    raise AssertionError('Detector did not finish')
try:
    run_once();before=records()
    check(len({p['type'] for p in before})==9,'Single invocation scanned all nine target types')
    actual={(p['type'],p['x'],p['y'],p['z']) for p in before}
    check({('spawner',0,100,0),('spawner',0,171,0),('rail',15,100,0),('rail',86,100,0)}<=actual,'Single sweep saves 71-block horizontal and vertical discoveries')
    check(('spawner',0,170,0) not in actual and ('rail',85,100,0) not in actual,'70-block discoveries suppressed')
    view=probe('inspect')
    check(view['state']=='已结束' and not view['captured'],'Process finishes and leaves physical input available')
    names={'spawner':'刷怪笼','end_portal_frame':'末地框架','iron_door':'铁门','iron_bars':'铁栏杆','iron_chain':'锁链','rail':'普通铁轨','reinforced_deepslate':'强化深板岩','vault':'试炼宝库','budding_amethyst':'紫水晶母岩'}
    points={(p['Name'],int(p['X']),int(p['Y']),int(p['Z'])) for p in view['waypoints']}
    check(all(('检测·'+names[p['type']],p['x'],p['y'],p['z']) in points for p in before),'All saved records have actual Xaero waypoints before exit')
    time.sleep(3)
    check(records()==before and not any(m['data'].get('name')=='目标方块检测' for m in call('bus.poll',observer)['messages']),'No recurring scans or extra exit broadcasts after completion')
    run_once();after=records()
    check(all(p in after for p in before),'A second invocation preserves persisted records')
    check(all(a['type']!=b['type'] or a['dimension']!=b['dimension'] or sum((a[k]-b[k])**2 for k in ('x','y','z'))>4900 for i,a in enumerate(after) for b in after[i+1:]),'A second invocation still deduplicates within 70 blocks')
    keys=[(p['Name'],p['X'],p['Y'],p['Z']) for p in probe('inspect')['waypoints']]
    check(len(keys)==len(set(keys)),'A second invocation does not duplicate waypoints')
    pathlib.Path('docs/block-detection-test/one-shot-results.json').write_text(json.dumps(dict(checks=checks,exit_events=events,records=after),ensure_ascii=False,indent=2))
finally:
    call('macro.exit',observer,reason='验收完成')
