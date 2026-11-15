import socket,json,time,pathlib,uuid
base=pathlib.Path('run-block-test/config'); checks=[]
def call(command, token=None, **params):
 req=dict(id=str(uuid.uuid4()),command=command,**params)
 if token:req['macro_token']=token
 with socket.create_connection(('127.0.0.1',25585),timeout=10) as s:
  s.settimeout(20);s.sendall((json.dumps(req)+'\n').encode());r=json.loads(s.makefile().readline())
 if not r['ok']:raise RuntimeError(r)
 return r['result']
def trigger(action,**p):
 ident=str(uuid.uuid4());(base/'macro-scheduling-test.trigger').write_text(json.dumps(dict(id=ident,action=action,**p)))
 for _ in range(100):
  time.sleep(.1)
  f=base/'macro-scheduling-test-observation.json'
  if f.exists():
   r=json.loads(f.read_text())
   if r['id']==ident:return r
 raise RuntimeError('trigger timeout')
def check(c,msg):
 assert c,msg
 checks.append(msg);print('PASS',msg,flush=True)

out=pathlib.Path('docs/mining-scheduler-10min');out.mkdir(exist_ok=True)
trigger('stop');trigger('resume');trigger('close_console')
call('chat.command',text='effect give @s minecraft:night_vision infinite 0 true')
time.sleep(1)
before=call('observe');inventory_before=call('inventory')
reg=call('macro.register',role='detection',name='十分钟实测观察')
token=reg['macro_token']
events=[];samples=[];errors=[]
start=time.monotonic()
try:
 trigger('start_program',file='mining-scheduler.jar');time.sleep(1)
 start=time.monotonic();trigger('start_program',file='fishbone-mining.jar')
 while time.monotonic()-start<600:
  elapsed=time.monotonic()-start
  events.extend(call('bus.poll',token).get('messages',[]))
  obs=call('observe',token)
  samples.append(dict(elapsed=elapsed,player=obs.get('player'),hand=obs.get('main_hand',{}).get('remaining_durability'),active=obs.get('active')))
  if any(e.get('data',{}).get('role')=='execution' and e['data'].get('event')=='exited' for e in events):
   errors.append('挖矿宏提前退出');break
  (out/'progress.json').write_text(json.dumps(dict(elapsed=elapsed,samples=samples,events=events),ensure_ascii=False,indent=2))
  if int(elapsed)//30>int(samples[-2]['elapsed'])//30 if len(samples)>1 else True:
   print(json.dumps(samples[-1],ensure_ascii=False),flush=True)
  time.sleep(min(2,max(0,600-(time.monotonic()-start))))
except Exception as e:errors.append(str(e))
finally:
 duration=time.monotonic()-start
 final=inventory_after=stopped=status=None
 try:
  final=call('observe',token);inventory_after=call('inventory',token)
 except Exception as e:errors.append('最终状态采集失败: '+str(e))
 finally:
  try:stopped=trigger('stop')
  except Exception as e:errors.append('停止宏失败: '+str(e))
  try:trigger('close_console')
  except Exception as e:errors.append('关闭控制台失败: '+str(e))
 try:status=call('status')
 except Exception as e:errors.append('清理后状态采集失败: '+str(e))
 delta=None
 if inventory_after is not None:
  counts_before=inventory_before.get('counts',{});counts_after=inventory_after.get('counts',{})
  delta={k:counts_after.get(k,0)-counts_before.get(k,0) for k in counts_before.keys()|counts_after.keys() if counts_after.get(k,0)!=counts_before.get(k,0)}
 report=dict(duration_seconds=duration,completed_600_seconds=duration>=600 and not errors,errors=errors,before=before,after=final,inventory_delta=delta,samples=samples,events=events,stopped=stopped,status=status,night_vision_command='effect give @s minecraft:night_vision infinite 0 true')
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(json.dumps(dict(duration_seconds=duration,errors=errors,inventory_delta=delta,status=status),ensure_ascii=False),flush=True)
