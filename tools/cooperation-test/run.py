#!/usr/bin/env python3
"""Run real three-process macro scenarios in an isolated Minecraft client."""
import argparse, importlib.util, json, shutil, subprocess, time, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('minectl', ROOT/'cli/minectl.py')
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
PORT = 25585

def request(command, **values):
    response = cli.exchange(dict(id=uuid.uuid4().hex, command=command, **values), PORT, 20)
    if not response['ok']: raise AssertionError(response)
    return response['result']

def wait_for(test, seconds=90):
    deadline = time.monotonic()+seconds
    last = None
    while time.monotonic() < deadline:
        try:
            result = test()
            if result: return result
        except (OSError, ValueError, AssertionError) as e: last = e
        time.sleep(.25)
    raise AssertionError(f'Timeout: {last}')

def click(label):
    widget = next(w for w in request('ui.list') if w['label']==label)
    request('ui.click', widget=widget['id'])

def read(path):
    try: return json.loads(path.read_text())
    except (OSError, ValueError): return {}

def run(version, attach=False, world_source=None):
    print(f'[{version}] building isolated test bridge', flush=True)
    logdir = ROOT/'docs/cooperation-test'/version; logdir.mkdir(parents=True, exist_ok=True)
    if not attach: subprocess.run([str(ROOT/'gradlew'), 'cooperationTestJar', '-I', 'tools/cooperation-test/probe.init.gradle', f'-Pmc={version}', '--offline', '--console=plain'], cwd=ROOT, stdout=(logdir/'build.log').open('w'), stderr=subprocess.STDOUT, check=True)
    game = ROOT/f'run-cooperation-{version}'; config = game/'config'; programs = config/'minecontrol-programs'
    programs.mkdir(parents=True, exist_ok=True); (game/'mods').mkdir(exist_ok=True); (game/'saves').mkdir(exist_ok=True)
    sources = {'1.20.1': ROOT/'run-issue1-1.20.1/saves/New World', '1.20.4': ROOT/'run-issue1-1.20.4/saves/Issue1-Regression', '26.2': ROOT/'run-26.2/saves/MineControl Observation Test', '26.3': ROOT/'run-26.3/saves/MineControl 26.3 Compatibility Test'}
    world = game/'saves/Cooperation Test'
    if not world.exists(): shutil.copytree(world_source or sources[version], world)
    (config/'minecontrol.json').write_text(json.dumps(dict(port=PORT, backgroundControl=True, blockPhysicalInputDuringMacros=True, requestTimeoutMs=15000, maxHoldTicks=1200)))
    if not (game/'options.txt').exists(): (game/'options.txt').write_text('lang:en_us\nonboardAccessibility:false\n')
    for jar in (ROOT/'build/macros/cooperation').glob('*.jar'): shutil.copy2(jar, programs/jar.name)
    shutil.copy2(ROOT/f'build/test-mods/cooperation-probe-mc{version}.jar', game/'mods'/f'cooperation-probe-mc{version}.jar')
    log = (logdir/('client-attach.log' if attach else 'client.log')).open('w')
    process = None if attach else subprocess.Popen([str(ROOT/'gradlew'), 'runClient', f'-Pmc={version}', f'-PrunDir={game.name}', '-x', 'downloadAssets', '--offline', '--console=plain'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    try:
        wait_for(lambda: request('status'), 120)
        print(f'[{version}] client responding', flush=True)
        if not request('status')['world_loaded']:
            if request('status')['screen'].endswith('AccessibilityOnboardingScreen'): click('Continue')
            wait_for(lambda: (request('status')['screen'] or '').endswith(('TitleScreen', 'SelectWorldScreen')))
            if (request('status')['screen'] or '').endswith('TitleScreen'): click('Singleplayer')
            wait_for(lambda: any(w['label']=='Play Selected World' for w in request('ui.list')))
            listing = next((w for w in request('ui.list') if w['type'] in ['WorldSelectionList', 'WorldListWidget']), dict(x=0, y=48, width=request('status')['gui_width']))
            request('mouse.goto', x=listing['x']+listing['width']/2, y=listing['y']+18)
            request('mouse.click', button='left')
            def select_world():
                if any(w['label']=='Play Selected World' and w['enabled'] for w in request('ui.list')): return True
                request('mouse.goto', x=listing['x']+listing['width']/2, y=listing['y']+18)
                request('mouse.click', button='left')
                return False
            wait_for(select_world)
            click('Play Selected World')
            # World upgrade dialogs, if any, apply only to this copied test world.
            def loaded():
                state = request('status')
                if state['world_loaded'] and state['screen'] is None: return state
                for widget in request('ui.list'):
                    if widget['label'] in ['I know what I\'m doing!', 'Proceed', 'Continue'] and widget['enabled']: request('ui.click', widget=widget['id'])
                return False
            wait_for(loaded, 120)
            if request('status')['screen'] is not None: request('key.tap', key='ESCAPE')
            wait_for(lambda: request('status')['screen'] is None)
        print(f'[{version}] test world loaded', flush=True)
        results = []
        for scenario in ['normal', 'pause', 'cascade']:
            report_path = programs/f'cooperation-report-{scenario}.json'
            report_path.unlink(missing_ok=True)
            (programs/'cooperation-scenario.txt').write_text(scenario)
            (config/'cooperation-start.trigger').touch()
            report = wait_for(lambda: read(report_path), 90)
            shutil.copy2(report_path, logdir/f'{scenario}.json')
            assert report['state']=='passed', report
            wait_for(lambda: read(config/'cooperation-state.json').get('active') is False, 15)
            state = request('status'); observation = read(config/'cooperation-state.json')
            assert not any('Exception in thread' in line or 'AssertionError' in line for line in observation['logs']), observation
            assert not state['held_keys'] and not state['held_buttons'] and not observation['captured'], (state, observation)
            results.append(dict(scenario=scenario, checks=len(report['checks']), all_processes_stopped=True, inputs_released=True, capture_released=True, workers_no_uncaught_errors=True))
            print(f'[{version}] PASS {scenario}: {len(report["checks"])} checks, all processes/input/capture cleaned', flush=True)
        (logdir/'summary.json').write_text(json.dumps(dict(minecraft=version, state='passed', scenarios=results), indent=2)+'\n')
        request('key.tap', key='ESCAPE')
        wait_for(lambda: any(w['label']=='Save and Quit to Title' for w in request('ui.list')))
        click('Save and Quit to Title')
        wait_for(lambda: (request('status')['screen'] or '').endswith('TitleScreen'))
        click('Quit Game')
        if process is not None: process.wait(timeout=30)
        print(f'[{version}] complete', flush=True)
    except BaseException:
        print(f'[{version}] FAILED; inspect {logdir}/client.log and {config}/cooperation-state.json', flush=True)
        raise
    finally:
        log.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--attach', action='store_true'); parser.add_argument('--world', type=Path, help='Source world to copy (single version only)'); parser.add_argument('versions', nargs='*', default=['1.20.1','1.20.4','26.2','26.3']); args=parser.parse_args()
    if args.world and len(args.versions) != 1: parser.error("--world requires exactly one version")
    for version in args.versions: run(version, args.attach, args.world)
