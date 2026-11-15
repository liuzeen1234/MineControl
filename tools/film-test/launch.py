import json,pathlib,subprocess,os,urllib.request,shutil,zipfile
REPO=pathlib.Path(__file__).resolve().parents[2]
ROOT=REPO/'run-film-test-1.20.1'
CACHE=pathlib.Path.home()/'.gradle/caches';MAVEN=CACHE/'modules-2/files-2.1'
def validate_download(path, suffix):
 if suffix=='.json':json.loads(path.read_text())
 elif suffix=='.jar':
  with zipfile.ZipFile(path) as archive:
   if archive.testzip() is not None:raise ValueError('Corrupt JAR: '+str(path))
def download(url,path):
 path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists():
  try:
   validate_download(path,path.suffix)
   return path
  except (ValueError,OSError,zipfile.BadZipFile):path.unlink()
 partial=path.with_name(path.name+'.part')
 try:
  subprocess.run(['curl','-fsSL','--retry','3','--max-time','120',url,'-o',str(partial)],check=True)
  validate_download(partial,path.suffix)
  partial.replace(path)
 finally:
  partial.unlink(missing_ok=True)
 return path
profile=ROOT/'setup/fabric-profile.json'
download('https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.19.3/profile/json',profile)
p=json.loads(profile.read_text());cp=[]
for lib in p['libraries']:
 group,name,version=lib['name'].split(':');filename=f'{name}-{version}.jar'
 matches=list((MAVEN/group/name/version).glob('*/'+filename))
 cp.append(str(matches[0] if matches else download(lib.get('url','https://maven.fabricmc.net/')+group.replace('.','/')+'/'+name+'/'+version+'/'+filename,ROOT/'setup/libraries'/filename)))
# Resolve this game's official libraries independently of the latest Gradle target.
metadata=json.loads((CACHE/'fabric-loom/1.20.1/minecraft-info.json').read_text())
for lib in metadata['libraries']:
 rules=lib.get('rules',[]);allowed=not rules
 for rule in rules:
  condition=rule.get('os',{})
  if condition.get('name','osx')=='osx' and condition.get('arch','aarch64') in ('aarch64','arm64'):
   allowed=rule['action']=='allow'
 if not allowed:continue
 artifact=lib.get('downloads',{}).get('artifact')
 if not artifact:continue
 group,name,version=lib['name'].split(':')[:3];filename=pathlib.Path(artifact['path']).name
 if 'natives-' in filename and 'natives-macos-arm64' not in filename:continue
 matches=list((MAVEN/group/name/version).glob('*/'+filename))
 cp.append(str(matches[0] if matches else download(artifact['url'],ROOT/'setup/libraries'/filename)))
for name in ['lwjgl','lwjgl-glfw','lwjgl-jemalloc','lwjgl-openal','lwjgl-opengl','lwjgl-stb','lwjgl-tinyfd']:
 matches=list((MAVEN/'org.lwjgl'/name/'3.3.1').glob('*/'+name+'-3.3.1-natives-macos-arm64.jar'))
 if not matches:matches=[download(f'https://libraries.minecraft.net/org/lwjgl/{name}/3.3.1/{name}-3.3.1-natives-macos-arm64.jar',ROOT/'setup/libraries'/f'{name}-3.3.1-natives-macos-arm64.jar')]
 cp.extend(map(str,matches))
cp.append(str(CACHE/'fabric-loom/1.20.1/minecraft-client.jar'))
shutil.copy2(REPO/'build/libs/minecontrol-mc1.20.1-1.0.0.jar',ROOT/'mods/minecontrol-mc1.20.1-1.0.0.jar')
java=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()+'/bin/java'
args=[java,'-XstartOnFirstThread','-Xmx4G','-Dfabric.development=false','-cp',':'.join(dict.fromkeys(cp)),p['mainClass'],'--username','FilmTest','--version','1.20.1','--gameDir',str(ROOT),'--assetsDir',str(CACHE/'fabric-loom/assets'),'--assetIndex','1.20.1-5','--accessToken','0','--userType','legacy','--width','1280','--height','720']
os.chdir(ROOT);os.execv(java,args)
