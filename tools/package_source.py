#!/usr/bin/env python3
"""Create a deterministic source handoff, retaining only inventoried upstream archives.

No publishing or uploading. Upstream source archives may retain their documented
public reference binaries/fixtures; GuardianAC runtime data never belongs here.
"""
import argparse
import hashlib
import json
import pathlib
import re
import stat
import zipfile

EXCLUDED_PARTS = {'.git', '.gradle', 'build', '__pycache__', '.venv'}
RUNTIME_PARTS = {'run', 'private', 'plugins', 'logs', 'world', 'world_nether',
                 'world_the_end', 'playerdata', 'advancements', 'stats', 'region',
                 'entities', 'poi', 'bans', 'backup', 'backups'}
FORBIDDEN_NAMES = {'ip-keys.bin', 'discord-webhook.txt', 'server.properties',
                   'eula.txt', 'banned-ips.json', 'banned-players.json',
                   'usercache.json', 'ops.json', 'whitelist.json',
                   '.netrc', '.npmrc', '.pypirc'}
FORBIDDEN_SUFFIXES = {'.class', '.pyc', '.log', '.key', '.pem', '.p12', '.pfx',
                      '.jks', '.keystore', '.bin', '.dat', '.dat_old', '.mca',
                      '.mcr', '.wal', '.shm', '.journal', '.lck', '.lock'}
DATABASE_SUFFIXES = {'.db', '.db3', '.sqlite', '.sqlite3'}
ARCHIVE_SUFFIXES = {'.zip', '.jar', '.war', '.ear', '.tar', '.gz', '.tgz',
                    '.bz2', '.tbz2', '.xz', '.txz', '.7z', '.rar', '.zst'}
INVENTORY_PATH = pathlib.PurePosixPath('third-party/sources/SOURCE-INVENTORY.json')
WRAPPER_PATH = 'gradle/wrapper/gradle-wrapper.jar'
EXPECTED_UPSTREAM_ARCHIVES = 21


def safe_relative(relative):
    value = relative.as_posix()
    if (relative.is_absolute() or '..' in relative.parts or '\\' in value
            or ':' in value or any(ord(c) < 32 or ord(c) == 127 for c in value)):
        raise ValueError('Unsafe source path: ' + repr(value))


def reject_links(root, relative):
    path = root
    if path.is_symlink():
        raise ValueError('Source archive rejects symbolic links: ' + str(path))
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('Source archive rejects symbolic links: ' + str(relative))


def archive_allowlist(root):
    reject_links(root, INVENTORY_PATH)
    inventory = json.loads((root / INVENTORY_PATH).read_text(encoding='utf-8'))
    records = inventory.get('archives')
    if not isinstance(records, list) or len(records) != EXPECTED_UPSTREAM_ARCHIVES:
        raise ValueError('Expected exactly 21 reviewed upstream source archives')
    allowed = {}
    for record in records:
        name = record.get('file', '')
        path = pathlib.PurePosixPath(name)
        safe_relative(path)
        digest = record.get('sha256', '')
        if (len(path.parts) != 1 or name != path.name
                or not name.endswith(('.zip', '.tar.gz'))
                or not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest)):
            raise ValueError('Invalid upstream archive inventory entry')
        relative = 'third-party/sources/' + name
        if relative in allowed:
            raise ValueError('Duplicate upstream archive inventory entry: ' + relative)
        allowed[relative] = digest
    return allowed


def looks_like_archive(data):
    signatures = (b'PK\x03\x04', b'PK\x05\x06', b'PK\x07\x08', b'\x1f\x8b',
                  b'BZh', b'\xfd7zXZ\x00', b'7z\xbc\xaf\x27\x1c',
                  b'Rar!\x1a\x07', b'\x28\xb5\x2f\xfd')
    return data.startswith(signatures) or data[257:262] == b'ustar'


def validate_file(path, relative, allowed):
    if path.stat().st_nlink > 1:
        raise ValueError('Source archive rejects hard-linked files: ' + str(relative))
    name = path.name.casefold()
    # Sidecars and backup suffixes do not make an embedded database safe.
    database = any(re.search(re.escape(suffix) + r'(?:$|[-.])', name)
                   for suffix in DATABASE_SUFFIXES)
    if (name in FORBIDDEN_NAMES or '.env' in name or database
            or any(name.endswith(suffix) for suffix in FORBIDDEN_SUFFIXES)):
        raise ValueError('Unexpected private/generated file: ' + str(relative))
    data = path.read_bytes()
    if data.startswith(b'SQLite format 3\x00'):
        raise ValueError('Database content is forbidden: ' + str(relative))
    posix = relative.as_posix()
    if posix == WRAPPER_PATH:
        return data
    is_archive = path.suffix.casefold() in ARCHIVE_SUFFIXES or looks_like_archive(data)
    if posix in allowed:
        if hashlib.sha256(data).hexdigest() != allowed[posix]:
            raise ValueError('Upstream source archive hash mismatch: ' + posix)
    elif is_archive:
        raise ValueError('Unreviewed nested archive: ' + posix)
    elif re.search(rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----', data):
        raise ValueError('Private-key PEM content is forbidden: ' + posix)
    return data


def sources(root):
    root = pathlib.Path(root)
    allowed = archive_allowlist(root)
    seen_archives = set()
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        safe_relative(relative)
        # Build/cache/VCS outputs are omitted; other symlinks are rejected.
        if any(part.casefold() in EXCLUDED_PARTS for part in relative.parts):
            continue
        reject_links(root, relative)
        if any(part.casefold() in RUNTIME_PARTS for part in relative.parts):
            raise ValueError('Runtime/private directory is forbidden: ' + str(relative))
        if not path.is_file():
            if not path.is_dir():
                raise ValueError('Only regular files/directories are allowed: ' + str(relative))
            continue
        validate_file(path, relative, allowed)
        if relative.as_posix() in allowed:
            seen_archives.add(relative.as_posix())
        yield path, relative
    if seen_archives != set(allowed):
        raise ValueError('Missing reviewed source archives: ' + ', '.join(sorted(set(allowed) - seen_archives)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    if output.is_relative_to(root):
        raise ValueError('Write the archive outside the source tree')
    if output.exists():
        raise ValueError('Preserve prior handoffs; choose a new output name')
    files = list(sources(root))
    allowed = archive_allowlist(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, relative in files:
            reject_links(root, relative)
            data = validate_file(path, relative, allowed)
            item = zipfile.ZipInfo('GuardianAC-Ultimate/' + relative.as_posix(), (1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.create_system = 3
            mode = 0o755 if relative.as_posix() == 'gradlew' or path.suffix == '.sh' else 0o644
            item.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(item, data, compresslevel=9)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None or len(archive.namelist()) != len(files):
            raise ValueError('Source archive verification failed')
    print(f'{hashlib.sha256(output.read_bytes()).hexdigest()}  {output.name}')
    print(f'{len(files)} files, {output.stat().st_size} bytes; no upload/publication performed')


if __name__ == '__main__':
    main()
