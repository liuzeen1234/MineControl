#!/usr/bin/env python3
"""Run against an isolated Minecraft 26.3 client at TitleScreen on port 25583."""
import importlib.util,json,time,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('cli',root/'cli/minectl.py');cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)
checks=[]
def req(command,**kw):
 r=cli.exchange(dict(id=uuid.uuid4().hex,command=command,**kw),25583,20)
 assert r['ok'],r
 return r['result']
def click(label):
 w=next(w for w in req('ui.list') if w['label']==label);req('ui.click',widget=w['id']);time.sleep(.3)
def check(label,value):
 assert value,label
 checks.append(label);print('PASS',label,flush=True)
check('ping',req('ping') is not None)
click('Options...');check('widget click opens OptionsScreen',req('status')['screen'].endswith('OptionsScreen'))
req('key.tap',key='ESCAPE');time.sleep(.3);check('SDL Escape closes options',req('status')['screen'].endswith('TitleScreen'))
w=next(w for w in req('ui.list') if w['label']=='Options...');req('mouse.goto',x=w['x']+w['width']/2,y=w['y']+w['height']/2);req('mouse.click',button='left');time.sleep(.3)
check('SDL scaled mouse move and left click',req('status')['screen'].endswith('OptionsScreen'))
req('key.tap',key='ESCAPE');time.sleep(.2)
req('key.down',key='LEFT_SHIFT');req('key.down',key='W');check('SDL held Shift+W',set(req('status')['held_keys'])=={225,26});req('input.reset');check('input reset',req('status')['held_keys']==[])
req('mouse.down',button='right');check('SDL right button down',req('status')['held_buttons']==[3]);req('mouse.up',button='right');check('SDL right button up',req('status')['held_buttons']==[])
for key in ['ENTER','PAGE_UP','PAGE_DOWN','LEFT_BRACKET','RIGHT_BRACKET','KP_0','KP_DECIMAL','KP_SUBTRACT','KP_DIVIDE']:
 req('key.down',key=key);req('input.reset')
check('legacy named key aliases accepted',True)
req('key.hold',key='W',ticks=3);time.sleep(.3);check('SDL timed keyboard release',not req('status')['held_keys'])
click('MineControl 宏');check('macro screen opens',req('status')['screen'].endswith('MacroScreen'));req('key.tap',key='ESCAPE');time.sleep(.3)
(root/'docs/compatibility-26.3/menu-results.json').write_text(json.dumps({'minecraft':'26.3','state':'passed','checks':checks},ensure_ascii=False,indent=2)+'\n')
