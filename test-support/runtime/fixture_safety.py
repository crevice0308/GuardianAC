"""Fail-closed safety checks for disposable localhost fixtures, independent of server startup."""
from pathlib import Path
import hashlib,os,re
from fixture_privacy import validate_privacy_config
ALLOWED_PLUGIN_JARS={'GuardianAC.jar','GuardianFixture.jar','RealClientFixture.jar','ModifierFixture.jar'}
FIXTURE_HOST='127.0.0.1'
PORT_ENV={'paper-26.3':'GUARDIAN_PAPER_PORT','purpur-26.3':'GUARDIAN_PURPUR_PORT'}
DEFAULT_PORTS={'paper-26.3':26731,'purpur-26.3':26732}
def fixture_ports(environ=None):
    """Choose a complete, unambiguous port pair without probing or reserving sockets.

    Overrides must be supplied together. Only canonical ASCII decimal strings are
    accepted; whitespace, signs, leading zeros, floats and host:port values fail.
    The defaults support the documented harness, not reuse of an interrupted run.
    """
    environment=os.environ if environ is None else environ
    supplied=[key in environment for key in PORT_ENV.values()]
    if any(supplied) and not all(supplied):
        raise RuntimeError('Set GUARDIAN_PAPER_PORT and GUARDIAN_PURPUR_PORT together')
    ports={}
    for variant,key in PORT_ENV.items():
        value=environment[key] if all(supplied) else str(DEFAULT_PORTS[variant])
        if not isinstance(value,str) or not re.fullmatch(r'[1-9][0-9]{3,4}',value):
            raise RuntimeError('Fixture port must be a canonical decimal integer: '+key)
        port=int(value)
        if not 1024<=port<=65535:
            raise RuntimeError('Fixture port must be between 1024 and 65535: '+key)
        ports[variant]=port
    if len(set(ports.values()))!=len(PORT_ENV):
        raise RuntimeError('Paper and Purpur fixture ports must be distinct')
    return ports
def fixture_port(variant,environ=None):
    if variant not in PORT_ENV:raise RuntimeError('Unknown fixture variant')
    return fixture_ports(environ)[variant]
def validate_client_port(port,environ=None):
    """Reject stale/unselected targets before the client can open any socket."""
    if type(port) is not int or port not in fixture_ports(environ).values():
        raise RuntimeError('Client port must match the selected Paper or Purpur fixture port')
    return port
def settings(variant):
    return {'server-ip':FIXTURE_HOST,'server-port':str(fixture_port(variant)),
            'online-mode':'false','enable-rcon':'false','enable-query':'false'}
def properties(path,strict=False):
    values={}
    for raw in Path(path).read_text().splitlines():
        line=raw.strip()
        if not line or line.startswith(('#','!')):continue
        if line.endswith('\\'):raise RuntimeError('Fixture property continuations are not accepted')
        match=re.match(r'^([^\s:=]+)\s*(?:[:=]\s*|\s+)(.*)$',line)
        key,value=(match.group(1),match.group(2).strip()) if match else (line,'')
        if '\\' in key:raise RuntimeError('Escaped fixture property keys are not accepted')
        if strict and key in values:raise RuntimeError('Duplicate fixture property key: '+key)
        values[key]=value
    return values
def enforce_properties(path,variant):
    path=Path(path)
    if path.is_symlink():raise RuntimeError('Fixture server.properties must not be a symlink')
    data=properties(path) if path.exists() else {}
    data.update(settings(variant));data.setdefault('view-distance','3');data.setdefault('simulation-distance','3')
    path.write_text('\n'.join(k+'='+v for k,v in sorted(data.items()))+'\n')
def validate_fixture(directory,expected_hash):
    directory=Path(directory)
    if directory.is_symlink():raise RuntimeError('Fixture root must not be a symlink')
    config=directory/'server.properties'
    if config.is_symlink():raise RuntimeError('Fixture server.properties must not be a symlink')
    actual=properties(config,strict=True)
    for key,value in settings(directory.name).items():
        if actual.get(key)!=value:raise RuntimeError('Unsafe or missing fixture setting: '+key)
    if properties(directory/'eula.txt',strict=True).get('eula')!='true':raise RuntimeError('Fixture EULA acceptance is missing')
    plugins=directory/'plugins'
    if plugins.is_symlink():raise RuntimeError('Fixture plugins must not be a symlink')
    for entry in plugins.rglob('*'):
        if entry.is_symlink():raise RuntimeError('Fixture plugin paths must not be symlinks')
        if entry.name.lower()=='discord-webhook.txt':raise RuntimeError('Fixture must not contain a Discord webhook secret')
        if entry.suffix.lower()=='.jar' and (entry.parent!=plugins or entry.name not in ALLOWED_PLUGIN_JARS):
            raise RuntimeError('Unexpected fixture plugin JAR: '+entry.name)
    jar=plugins/'GuardianAC.jar'
    if not re.fullmatch(r'[0-9a-f]{64}',expected_hash or '') or not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest()!=expected_hash:
        raise RuntimeError('Fixture GuardianAC artifact does not match the authorized SHA-256')

    validate_privacy_config(directory)
