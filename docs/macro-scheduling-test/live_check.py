"""Run against a dedicated, loaded 26.2 test client. Uses only the local MineControl API."""
import concurrent.futures
import argparse
import json
import pathlib
import socket
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=25583)
parser.add_argument('--probe', action='store_true')
parser.add_argument('--run-dir', default='run-input-test')
args = parser.parse_args()
root = pathlib.Path(args.run_dir) / 'config' / 'minecontrol-programs'
root.mkdir(parents=True, exist_ok=True)
checks = []

def probe(action='snapshot'):
    config = root.parent
    identifier = str(uuid.uuid4())
    (config / 'macro-scheduling-test.trigger').write_text(json.dumps({'id': identifier, 'action': action}))
    def observation():
        try:
            report = json.loads((config / 'macro-scheduling-test-observation.json').read_text())
            return report if report['id'] == identifier else None
        except (FileNotFoundError, json.JSONDecodeError):
            return None
    return await_value(observation, 5)


def call(command, token=None, **params):
    request = dict(params, id=str(uuid.uuid4()), command=command)
    if token:
        request['macro_token'] = token
    with socket.create_connection(('127.0.0.1', args.port), timeout=5) as connection:
        connection.settimeout(20)
        connection.sendall((json.dumps(request) + '\n').encode())
        reply = json.loads(connection.makefile('r', encoding='utf-8').readline())
    if not reply['ok']:
        raise RuntimeError(reply['error']['code'] + ': ' + reply['error']['message'])
    return reply['result']

def check(condition, text):
    if not condition:
        raise AssertionError(text)
    checks.append(text)
    print('PASS', text, flush=True)

def await_value(fn, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError('Timed out waiting for macro transition')

for name in ('SchedulingDetectorA', 'SchedulingDetectorB'):
    (root / (name + '.java')).write_text('''
import dev.minecontrol.sdk.*;
import com.google.gson.*;
class NAME {
 public static void main(String[] args) throws Exception {
  try(var mc = MineControl.connect(Integer.parseInt(args[0]), MineControl.MacroRole.DETECTION, "NAME")) {
   boolean denied = false;
   try { mc.key().down("left_shift"); } catch(CommandException e) { denied = e.code().equals("FORBIDDEN"); }
   JsonObject data = new JsonObject(); data.addProperty("permission_denied", denied);
   data.addProperty("world_loaded", mc.status().worldLoaded());
   mc.broadcast("test.ready", data);
   while(!mc.stopRequested()) Thread.sleep(50);
   mc.exitReason("检测宏收到调度停止");
  }
 }
}
'''.replace('NAME', name), encoding='utf-8')
(root / 'SchedulingExecution.java').write_text('''
import dev.minecontrol.sdk.*;
import com.google.gson.*;
class SchedulingExecution {
 public static void main(String[] args) throws Exception {
  try(var mc = MineControl.connect(Integer.parseInt(args[0]), MineControl.MacroRole.EXECUTION, "执行测试")) {
   mc.subscribe("test.signal"); mc.key().down("left_shift");
   mc.broadcast("test.ready", new JsonPrimitive("execution"));
   while(!mc.stopRequested()) {
    for(var m : mc.pollBroadcasts().messages()) if(m.channel().equals("test.signal")) mc.broadcast("test.echo", m.data());
    Thread.sleep(50);
   }
   mc.exitReason("完成测试，释放按键");
  }
 }
}
''', encoding='utf-8')
(root / 'SchedulingCrash.java').write_text('''
import dev.minecontrol.sdk.*;
class SchedulingCrash {
 public static void main(String[] args) throws Exception {
  var mc = MineControl.connect(Integer.parseInt(args[0]), MineControl.MacroRole.DETECTION, "异常退出测试");
  throw new IllegalStateException("deliberate failure");
 }
}
''', encoding='utf-8')
(root / 'SchedulingContender.java').write_text('''
import dev.minecontrol.sdk.*;
import java.nio.file.*;
class SchedulingContender {
 public static void main(String[] args) throws Exception {
  try(var mc = MineControl.connect(Integer.parseInt(args[0]), MineControl.MacroRole.EXECUTION, "冲突执行测试")) {
   Files.writeString(Path.of("contender-result.txt"), "UNEXPECTED_SUCCESS");
  } catch(CommandException e) { Files.writeString(Path.of("contender-result.txt"), e.code()); }
 }
}
''', encoding='utf-8')
if args.probe:
    probe('resume')
# Opening the macro list refreshes newly written test files.
call('key.tap', key='f8')
time.sleep(2)
call('key.tap', key='escape')
time.sleep(.3)
check(call('status')['world_loaded'], 'test client has a loaded world')
scheduler = call('macro.register', role='scheduler', name='实测调度')
token = scheduler['macro_token']
for channel in ('test.ready', 'test.echo'):
    call('bus.subscribe', token, channel=channel)
messages = []

def receive():
    messages.extend(call('bus.poll', token)['messages'])
    return messages

def instance(name):
    return next((m for m in call('program.list', token) if m['name'] == name and m['role'] != 'registering'), None)

try:
    for name in ('SchedulingDetectorA.java', 'SchedulingDetectorB.java'):
        call('program.start', token, file=name)
    await_value(lambda: instance('SchedulingDetectorA') and instance('SchedulingDetectorB'))
    await_value(lambda: sum(m['channel'] == 'test.ready' for m in receive()) >= 2)
    check(all(m['data']['permission_denied'] and m['data']['world_loaded'] for m in messages if m['channel'] == 'test.ready'),
          'two real SDK detection processes read state and are denied game input')
    check(not call('status')['held_keys'], 'detectors do not hold game inputs')
    if args.probe:
        check(not probe()['captured'], 'detectors leave real client physical input uncaptured')
    call('program.start', token, file='SchedulingCrash.java')
    await_value(lambda: any(m['channel'] == '$system' and m['data']['event'] == 'exited' and m['data']['name'] == '异常退出测试' for m in receive()))
    crash = next(m['data'] for m in messages if m['channel'] == '$system' and m['data']['event'] == 'exited' and m['data']['name'] == '异常退出测试')
    check(crash['exit_type'] == 'failed' and 'deliberate failure' in crash['reason'], 'actual crashing SDK process broadcasts failure instead of default normal shutdown')
    call('macro.run', script=[{'wait': 1000}])
    try:
        call('macro.register', role='execution', name='JSON互斥测试')
        raise AssertionError('JSON macro did not reserve execution slot')
    except RuntimeError as e:
        check(str(e).startswith('MACRO_BUSY'), 'legacy JSON execution blocks Java executor admission')
    call('macro.stop')
    def compete(name):
        try:
            return call('macro.register', role='execution', name=name)
        except RuntimeError as e:
            return str(e)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(compete, ['并发执行申请A', '并发执行申请B']))
    winners = [r for r in results if isinstance(r, dict)]
    try:
        check(len(winners) == 1 and sum(isinstance(r, str) and r.startswith('MACRO_BUSY') for r in results) == 1,
              'simultaneous connections admit exactly one executor')
    finally:
        for winner in winners:
            call('macro.exit', winner['macro_token'], reason='并发申请检查结束')
    call('program.start', token, file='SchedulingExecution.java')
    execution = await_value(lambda: instance('执行测试'))
    await_value(lambda: 'left_shift' in [str(k).lower() for k in call('status')['held_keys']] or 340 in call('status')['held_keys'])
    check(True, 'one execution process holds an actual simulated shift key alongside two detectors')
    if args.probe:
        state = probe()
        check(state['captured'] and state['shift_down'] and state['cursor_mode'] == 212993, 'executor captures input and releases the OS cursor in the real client')
        state = probe('console')
        check(state['console_open'] and state['captured'] and not state['physical_blocked'], 'F8 management console remains operable while an execution macro runs')
        state = probe('close_console')
        check(state['physical_blocked'], 'closing macro console restores physical game input blocking')
    result = root / 'contender-result.txt'
    if result.exists():
        result.unlink()
    call('program.start', token, file='SchedulingContender.java')
    await_value(result.exists)
    check(result.read_text() == 'MACRO_BUSY', 'second execution process receives MACRO_BUSY')
    call('bus.subscribe', token, channel='test.signal')
    call('bus.publish', token, channel='test.signal', data={'signal': 'hello', 'value': 42})
    await_value(lambda: any(m['channel'] == 'test.echo' for m in receive()))
    check(any(m['channel'] == 'test.echo' and m['data']['value'] == 42 for m in messages), 'named channel round-trip through a real SDK execution process')
    call('program.stop', token, instance_id=execution['instance_id'], reason='调度切换执行宏')
    await_value(lambda: not instance('执行测试'))
    await_value(lambda: any(m['channel'] == '$system' and m['data']['event'] == 'exited' and m['data']['name'] == '执行测试' for m in receive()))
    event = next(m['data'] for m in messages if m['channel'] == '$system' and m['data']['event'] == 'exited' and m['data']['name'] == '执行测试')
    check(event['reason'] == '完成测试，释放按键' and event['requested_reason'] == '调度切换执行宏' and event['requested_by'] == scheduler['instance_id'], 'global exit includes name, own reason and scheduler stop reason')
    check(not call('status')['held_keys'], 'execution exit releases actual held input')
    if args.probe:
        state = probe()
        check(not state['captured'] and not state['shift_down'], 'executor exit restores manual input without stopping detectors')
    check(instance('SchedulingDetectorA') and instance('SchedulingDetectorB'), 'execution exit preserves both detectors')
    call('program.start', token, file='SchedulingExecution.java')
    await_value(lambda: instance('执行测试'))
    check(True, 'execution slot can be reused after cleanup')
    call('system.pause', token, reason='实测暂停')
    await_value(lambda: len(call('program.list', token)) == 1)
    try:
        call('macro.register', role='detection', name='暂停后的检测')
        raise AssertionError('Paused system allowed a new macro')
    except RuntimeError as e:
        check(str(e).startswith('SYSTEM_DISABLED'), 'paused system rejects new registrations while scheduler remains alive')
    call('system.resume', token)
    call('program.start', token, file='SchedulingDetectorA.java')
    await_value(lambda: instance('SchedulingDetectorA'))
    check(True, 'scheduler resumes system and launches a detector again')
    call('macro.exit', token, reason='实测调度正常结束')
    token = None
    time.sleep(1)
    observer = call('macro.register', role='scheduler', name='结束检查')
    token = observer['macro_token']
    await_value(lambda: len(call('program.list', token)) == 1)
    check(not call('status')['held_keys'], 'scheduler exit stops remaining business macros')
    if args.probe:
        call('program.start', token, file='SchedulingDetectorB.java')
        await_value(lambda: instance('SchedulingDetectorB'))
        probe('stop')
        try:
            call('macro.register', role='detection', name='禁止自动重启')
            raise AssertionError('Emergency stop did not lock system')
        except RuntimeError as e:
            check(str(e).startswith('SYSTEM_DISABLED'), 'F9 polling path stops a detector-only system and prevents automatic restart')
        call('macro.exit', token, reason='紧急停止检查完成')
        token = None
        check(not probe()['captured'] and not call('status')['held_keys'], 'emergency stop leaves all input released')
finally:
    if token:
        try:
            call('system.pause', token, reason='测试清理')
            call('macro.exit', token, reason='测试清理完成')
        except Exception as e:
            print('cleanup:', e)
    pathlib.Path('docs/macro-scheduling-test/results.json').write_text(json.dumps({'checks': checks, 'passed': len(checks)}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
