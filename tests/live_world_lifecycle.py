#!/usr/bin/env python3
"""Opt-in destructive test on a disposable copied world; see docs/MACRO-SCHEDULING.md."""
import socket,json,time,uuid,pathlib,argparse,shutil
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run-dir',default='run-lifecycle-check')
parser.add_argument('--port',type=int,default=25589)
parser.add_argument('--prepare-from',type=pathlib.Path,help='Copy an idle disposable test world and create probe programs, then exit')
options=parser.parse_args()
run_dir=pathlib.Path(options.run_dir)
root=run_dir/'config'
if options.prepare_from:
    programs=root/'minecontrol-programs'
    programs.mkdir(parents=True,exist_ok=True)
    shutil.copytree(options.prepare_from,run_dir/'saves/Lifecycle')
    (root/'minecontrol.json').write_text(json.dumps(dict(enabled=True,port=options.port,backgroundControl=True,maxHoldTicks=1200,requestTimeoutMs=15000,maxRequestBytes=65536)))
    for role in ['SCHEDULER','DETECTION','EXECUTION']:
        name='Lifecycle'+role
        value='Long.toString(mc.status().tick())' if role=='SCHEDULER' else 'mc.position().dimension()'
        (programs/f'{name}.java').write_text('import dev.minecontrol.sdk.MineControl; class '+name+' { public static void main(String[] args) throws Exception { try (var mc=MineControl.connect(Integer.parseInt(args[0]),MineControl.MacroRole.'+role+',"'+name+'")) { while (!mc.stopRequested()) { java.nio.file.Files.writeString(java.nio.file.Path.of("'+name+'.progress"),'+value+'); Thread.sleep(100); } } } }')
    mods=run_dir/'mods'
    mods.mkdir(exist_ok=True)
    shutil.copyfile('build/test-mods/minecontrol-input-capture-test.jar',mods/'minecontrol-input-capture-test.jar')
    print('Prepared isolated world. Launch runClient with this run-dir and --quickPlaySingleplayer Lifecycle, then run this script without --prepare-from.')
    raise SystemExit(0)
def call(command,token=None,**args):
    req=dict(id=str(uuid.uuid4()),command=command,**args)
    if token: req['macro_token']=token
    with socket.create_connection(('127.0.0.1',options.port),timeout=3) as s:
        s.settimeout(20);s.sendall((json.dumps(req)+'\n').encode());r=json.loads(s.makefile().readline())
    assert r['ok'],r
    return r['result']
def bridge(action,**args):
    ident=str(uuid.uuid4());p=root/'world-lifecycle.request.json';tmp=p.with_suffix('.tmp');tmp.write_text(json.dumps(dict(id=ident,action=action,**args)));tmp.replace(p)
    end=time.monotonic()+30
    while time.monotonic()<end:
        try:
            r=json.loads((root/'world-lifecycle.response.json').read_text())
            if r.get('id')==ident:
                assert r['ok'],r
                return
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.1)
    raise AssertionError('bridge timed out: '+action)
def until(f,seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            if f():return
        except (OSError,AssertionError):pass
        time.sleep(.2)
    raise AssertionError('condition timeout')
if __name__=='__main__':
    until(lambda: call('status')['world_loaded'],60)
    bridge('prepare');time.sleep(1);bridge('ready')
    sched=call('macro.register',role='scheduler',name='Lifecycle observer')['macro_token']
    call('macro.run',script=[{'repeat':100000,'do':[{'command':'position'},{'wait':2}]}])
    until(lambda:call('macro.status')['steps']>3)
    bridge('loading');time.sleep(.3)
    before=call('macro.status');time.sleep(2);after=call('macro.status')
    assert before['state']==after['state']=='running' and before['steps']==after['steps'],(before,after)
    bridge('ready');until(lambda:call('macro.status')['steps']>after['steps'])
    for dim in ['minecraft:the_nether','minecraft:overworld']:
        bridge('teleport',dimension=dim)
        until(lambda: call('position').get('dimension')==dim)
        assert call('macro.status')['state']=='running'
    call('macro.stop');call('macro.exit',sched,reason='Start managed scheduler')
    bridge('start',file='LifecycleSCHEDULER.java')
    observer=call('macro.register',role='detection',name='Lifecycle observer')['macro_token']
    time.sleep(4)
    bridge('start',file='LifecycleDETECTION.java');bridge('start',file='LifecycleEXECUTION.java')
    time.sleep(4)
    bridge('loading');time.sleep(2);bridge('ready');time.sleep(1)
    for dim in ['minecraft:the_nether','minecraft:overworld']:
        bridge('teleport',dimension=dim)
        until(lambda:call('position',observer).get('dimension')==dim)
    progress=root/'minecontrol-programs/LifecycleSCHEDULER.progress'
    scheduler_before=progress.read_text()
    for role in ['DETECTION','EXECUTION']:
        until(lambda: (root/f'minecontrol-programs/Lifecycle{role}.progress').read_text()=='minecraft:overworld')
    bridge('disconnect')
    until(lambda:not call('status')['world_loaded'])
    time.sleep(5)
    assert progress.read_text()!=scheduler_before, 'scheduler stopped making progress outside world'
    logs=(run_dir/'logs/latest.log').read_text()
    assert '[LifecycleDETECTION.java] 已停止' in logs,logs[-4000:]
    assert '[LifecycleEXECUTION.java] 已停止' in logs,logs[-4000:]
    assert '[LifecycleSCHEDULER.java] 已停止' not in logs and '[LifecycleSCHEDULER.java] 运行失败' not in logs,logs[-4000:]
    print('PASS: JSON loading pause/resume, two real dimension transfers, Java SDK loading retry, detector/executor stop on real disconnect, managed scheduler survives in menu',flush=True)
    (run_dir/'lifecycle-report.json').write_text(json.dumps({'result':'passed','checks':['JSON loading pause/resume','JSON real dimension transfer x2','Java macros real dimension transfer x2','Java SDK loading wait','real disconnect stops detection and execution','scheduler survives world exit']},indent=2))
