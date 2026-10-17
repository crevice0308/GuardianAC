from pathlib import Path
from fixture_safety import enforce_properties,validate_fixture,fixture_port,FIXTURE_HOST
from fixture_privacy import initialize_privacy,privacy_preflight,PRIVACY_JVM_ARGS
from fixture_process import FixtureProcess,configured_max_seconds,UNKNOWN_EXIT
from terminal_gates import normal_lifecycle,write_report
import datetime, hashlib, json, os, queue, re, shutil, subprocess, threading, time, zipfile, urllib.parse
SCRIPT_DIR=Path(__file__).resolve().parent
ROOT=Path(os.environ.get('GUARDIAN_RUNTIME_OUTPUT',str(Path.cwd()/'guardianac-runtime-evidence'))).resolve()
PROJECT=Path(os.environ['GUARDIAN_PROJECT_ROOT']).resolve()
JAVA=Path(os.environ['JAVA_HOME']).resolve()/'bin/java'
BASELINES={'paper-26.3':Path(os.environ['GUARDIAN_PAPER_BASELINE']).resolve(),
           'purpur-26.3':Path(os.environ['GUARDIAN_PURPUR_BASELINE']).resolve()}
FIXTURE_JAR=Path(os.environ.get('GUARDIAN_FIXTURE_JAR',str(ROOT/'fixture-tools/GuardianFixture.jar'))).resolve()
def authorization():
    if os.environ.get('GUARDIAN_ACCEPT_MINECRAFT_EULA')!='true':
        raise RuntimeError('Read https://www.minecraft.net/en-us/eula and explicitly set GUARDIAN_ACCEPT_MINECRAFT_EULA=true only if you agree before starting a server.')
    ROOT.mkdir(parents=True,exist_ok=True)
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def stamp(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def prepare(variant,run='initial'):
    fixture_port(variant)  # Validate the complete selected pair before any writes.
    authorization()
    if variant not in BASELINES or not re.fullmatch(r'[A-Za-z0-9_-]+',run):raise ValueError('Invalid variant or run name')
    out=ROOT/run/variant
    if any(out.resolve().is_relative_to(baseline) or baseline.is_relative_to(out.resolve()) for baseline in BASELINES.values()):
        raise RuntimeError('Fixture output must not overlap read-only baseline directories')
    if not out.exists():
        shutil.copytree(BASELINES[variant],out,ignore=shutil.ignore_patterns('logs','*.log','.paper-remapped','tmp','plugins'))
        (out/'plugins').mkdir()
        enforce_properties(out/'server.properties',variant)
        (out/'eula.txt').write_text('eula=true\n')
        artifact=PROJECT/'build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar'
        expected=os.environ.get('GUARDIAN_PLUGIN_SHA256','')
        if not re.fullmatch(r'[0-9a-f]{64}',expected) or sha(artifact)!=expected:raise RuntimeError('Set GUARDIAN_PLUGIN_SHA256 to the exact independently verified artifact SHA-256')
        shutil.copy2(artifact,out/'plugins/GuardianAC.jar')
        initialize_privacy(out)
    if sha(out/'plugins/GuardianAC.jar')!=os.environ.get('GUARDIAN_PLUGIN_SHA256'):raise RuntimeError('Existing fixture does not contain the expected artifact')
    # Existing evidence is validated, never repaired or rebound to another port.
    validate_fixture(out,os.environ.get('GUARDIAN_PLUGIN_SHA256',''))
    return out
class Server:
    def __init__(self,directory,phase):
        self.max_seconds=configured_max_seconds()
        self.shutdown_result=None;self._stop_result=None;self._reader_error=None
        authorization()
        if not re.fullmatch(r'[A-Za-z0-9_-]+',phase):raise ValueError('Invalid fixture phase')
        self.directory=Path(directory); self.lines=[]; self.q=queue.Queue(); self.logpath=self.directory.parent/(self.directory.name+'-'+phase+'.log')
        privacy_path=self.directory.parent/(self.directory.name+'-'+phase+'-privacy.json')
        self.lifecycle_path=self.directory.parent/(self.directory.name+'-'+phase+'-lifecycle.json')
        if any(path.exists() for path in (self.logpath,privacy_path,self.lifecycle_path,self.lifecycle_path.with_suffix('.events.jsonl'))):raise RuntimeError('Preserve existing evidence; use a new run name')
        validate_fixture(self.directory,os.environ.get('GUARDIAN_PLUGIN_SHA256',''))
        self.privacy=privacy_preflight(self.directory)
        # Configuration and reviewed bytes must pass before even `java -version`.
        version=subprocess.check_output([str(JAVA),'-version'],stderr=subprocess.STDOUT,text=True,timeout=20)
        if not re.search(r'version "25[."]',version):raise RuntimeError('JDK25 is required')
        proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
        args=[str(JAVA),*PRIVACY_JVM_ARGS,'-Dguardian.fixture.variant='+self.directory.name,'-XX:ActiveProcessorCount=2','-Xms256M','-Xmx768M']
        if Path('/etc/ssl/certs/java/cacerts').exists():args.append('-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts')
        if proxy.hostname: args += ['-Dhttps.proxyHost='+proxy.hostname,'-Dhttps.proxyPort='+str(proxy.port)]
        # Recheck after the version subprocess and immediately before server start.
        validate_fixture(self.directory,os.environ.get('GUARDIAN_PLUGIN_SHA256',''))
        self.privacy=privacy_preflight(self.directory)
        privacy_path.write_text(json.dumps(self.privacy,indent=2)+'\n')
        self.log=self.logpath.open('w')
        try:
            self.p=FixtureProcess(args+['-jar','server.jar','--nogui'],cwd=self.directory,
                record_path=self.lifecycle_path,max_seconds=self.max_seconds,
                binding_sha256={'plugin':sha(self.directory/'plugins/GuardianAC.jar'),
                    'server':sha(self.directory/'server.jar'),'privacy_descriptor':sha(privacy_path)},
                target={'host':FIXTURE_HOST,'port':fixture_port(self.directory.name),'variant':self.directory.name})
            def read():
                try:
                    for l in self.p.stdout:
                        self.lines.append(l); self.log.write(l); self.log.flush(); self.q.put(l)
                        if any(k in l for k in ['Guardian','ERROR','Done (','GACTEST','Exception']):print(l.strip(),flush=True)
                except Exception as error:
                    self._reader_error=type(error).__name__
                    try:self.p.abort()
                    except (OSError,ValueError):pass
            self.thread=threading.Thread(target=read,daemon=True); self.thread.start()
        except BaseException:
            if hasattr(self,'p'):
                try:self.p.abort();self.p.stop()
                except BaseException:pass
            self.log.close()
            raise
    def expect(self,pattern,timeout=150,start=0):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if self._reader_error:raise RuntimeError('Fixture log reader failed: '+self._reader_error)
            for l in self.lines[start:]:
                if re.search(pattern,l):return l
            if self.p.poll() is not None:raise RuntimeError('server exited waiting '+pattern)
            if any('plugin is disabled' in l for l in self.lines[start:]):raise RuntimeError('GuardianAC disabled')
            time.sleep(.2)
        raise TimeoutError(pattern)
    def cmd(self,cmd):
        start=len(self.lines); self.log.write('FIXTURE COMMAND: '+cmd+'\n');self.log.flush();self.p.stdin.write(cmd+'\n');self.p.stdin.flush();return start
    def stop(self):
        if self._stop_result is not None:return self._stop_result
        try:
            self._stop_result=self.p.stop()
        finally:
            self.shutdown_result=self.p.lifecycle()
            self.thread.join(3)
            if not self.thread.is_alive():
                self.p.stdout.close();self.log.close()
            try:self.p.stdin.close()
            except OSError:pass
        if self._reader_error or self.thread.is_alive():self._stop_result=UNKNOWN_EXIT
        return self._stop_result
if __name__=='__main__':
    import sys
    variant=sys.argv[1]; run=sys.argv[2] if len(sys.argv)>2 else 'initial';d=prepare(variant,run);s=Server(d,'boot')
    result={'variant':variant,'time':stamp(),'plugin_sha256':sha(d/'plugins/GuardianAC.jar'),'server_sha256':sha(d/'server.jar')}
    try:
        s.expect('Done \\(');result['boot']=True
        for cmd,expected in [('gac status','rules'),('gac','/gac status'),('guardian reload',r'GuardianAC の設定を読み込みました。|GuardianAC configuration loaded\.'),('gac unban MissingFixture',r'有効なGuardianAC BANはありません。|No active GuardianAC ban found\.'),('gac confirm invalid',r'確認が期限切れ、または無効です。|Confirmation expired or invalid\.'),('gac replay invalid',r'ケースIDが無効です。|Invalid case ID\.')]:
            n=s.cmd(cmd);result[cmd]=s.expect(expected,start=n).strip()
        result['enabled']=any('internal development build enabled' in l for l in s.lines)
        result['run_completed']=True
    except (Exception,KeyboardInterrupt) as e:result['error']=str(e)
    finally:
        try:result['exit']=s.stop();result['exit_lifecycle']=s.shutdown_result
        except (Exception,KeyboardInterrupt) as error:result['shutdown_error']=type(error).__name__;result['exit']=None
        write_report(d.parent/(variant+'-smoke.json'),result,ensure_ascii=False);print(json.dumps(result,ensure_ascii=False))
    if ('error' in result or 'shutdown_error' in result or result.get('run_completed') is not True
            or result.get('boot') is not True or result.get('enabled') is not True
            or type(result.get('exit')) is not int or result['exit']!=0
            or not normal_lifecycle(result.get('exit_lifecycle'))):raise SystemExit(1)
