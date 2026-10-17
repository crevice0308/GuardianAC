#!/usr/bin/env python3
"""One-time, fail-closed hydration and fixed-asset release helper.

hydrate/prepare are offline. publish alone uses gh and GH_TOKEN. No operation
updates main, replaces a tag/release, retries a mutation, or removes evidence.
The coordinating operator must CAS main from staging_sha to clean_commit only
after independently verifying the published release. Do not rerun a failed job.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
from urllib.parse import quote, urlsplit
import urllib.error
import urllib.request

REPOSITORY = 'crevice0308/GuardianAC'
TAG = 'v1.0.0-dev.1'
SOURCE_COUNT = 296
BUILD_COUNT = 155
PART_BYTES = 4 * 1024 * 1024
JAR_NAME = 'GuardianAC-Ultimate-1.0.0-dev.1-fb56fa5d8fcc.jar'
JAR_SIZE = 18399808
JAR_SHA = 'fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1'
ZIP_NAME = 'GuardianAC-Ultimate-1.0.0-dev.1-fb56fa5d8fcc-public-source.zip'
ZIP_SIZE = 57917310
ZIP_SHA = '1050c0bf4f8c962c3241b2db53723a280fba496d375555c0bf8c65f516e01213'
MANIFEST_SHA = '80175005474ee31211c653c4c50b579280d1a3794d55f6638100ab39efb652fd'
EXPECTED_TREE_SHA = 'e679ac378ecedb4c1db97efc5727dd083a647231'
CI_PATH = '.github/workflows/ci.yml'
CI_INPUT = 'release-input/original-ci.yml'
LARGE_SOURCE_PATHS = frozenset({
    'third-party/sources/sqlite-jdbc-3.53.4.0-sources.zip',
    'third-party/sources/sqlite-src-3530400.zip',
    'third-party/sources/sqlite-jdbc-3.53.4.0.tar.gz',
})
ASSET_NAMES = {JAR_NAME, ZIP_NAME, 'SHA256SUMS', 'SOURCE-CORRESPONDENCE.json'}


class GateError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git_blob_sha(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()


def safe_relative(value, *, payload=False):
    require(isinstance(value, str) and bool(value), 'Empty/non-string path')
    path = PurePosixPath(value)
    require(bool(path.parts) and not path.is_absolute() and path.as_posix() == value,
            'Non-canonical path: ' + repr(value))
    require(all(part not in ('', '.', '..', '.git') for part in path.parts),
            'Unsafe path component: ' + repr(value))
    require('\\' not in value and ':' not in value
            and not any(ord(c) < 32 or ord(c) == 127 for c in value),
            'Unsafe path characters: ' + repr(value))
    if payload:
        require(value.startswith('release-input/'), 'Payload outside release-input')
    else:
        require(path.parts[0] != 'release-input', 'Payload in clean source')
        require(not value.startswith('.github/workflows/') or value == CI_PATH,
                'Unexpected workflow in clean source')
    return path


def checked_path(root, relative, *, payload=False):
    relative = safe_relative(relative, payload=payload)
    root = Path(root)
    require(not root.is_symlink(), 'Symlink root')
    current = root
    for component in relative.parts:
        current = current / component
        require(not current.is_symlink(), 'Symlink path: ' + str(relative))
    require(current.resolve().is_relative_to(root.resolve()), 'Path escaped root')
    return current


def read_bytes(root, relative, *, payload=False, expected_size=None):
    path = checked_path(root, relative, payload=payload)
    require(path.is_file() and stat.S_ISREG(path.stat().st_mode), 'Not a regular file: ' + relative)
    require(path.stat().st_nlink == 1, 'Hard-linked input: ' + relative)
    if expected_size is None:
        return path.read_bytes()
    require(path.stat().st_size == expected_size, 'Size mismatch before read: ' + relative)
    with path.open('rb') as stream:
        data = stream.read(expected_size + 1)
    require(len(data) == expected_size, 'File size changed during read: ' + relative)
    return data


def verify_bytes(data, row, label):
    require(len(data) == row['bytes'], 'Size mismatch: ' + label)
    require(sha256(data) == row['sha256'], 'SHA-256 mismatch: ' + label)
    if 'git_blob_sha' in row:
        require(git_blob_sha(data) == row['git_blob_sha'], 'Git blob mismatch: ' + label)


def check_digest(value, length):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{' + str(length) + '}', value) is not None


def validate_size_hash(row):
    require(type(row.get('bytes')) is int and row['bytes'] >= 0, 'Invalid size')
    require(check_digest(row.get('sha256'), 64), 'Invalid SHA-256')


def validate_parts(row, used_paths):
    parts = row.get('parts')
    require(isinstance(parts, list) and len(parts) >= 2, 'Missing split parts')
    for index, part in enumerate(parts):
        require(set(part) == {'index', 'path', 'bytes', 'sha256'}, 'Unexpected part fields')
        require(type(part['index']) is int and part['index'] == index, 'Out-of-order split parts')
        safe_relative(part['path'], payload=True)
        require(part['path'].startswith('release-input/parts/'), 'Part outside parts directory')
        require(part['path'] not in used_paths, 'Duplicate split-part path')
        used_paths.add(part['path'])
        validate_size_hash(part)
        require(0 < part['bytes'] <= PART_BYTES, 'Invalid part size')
        require(index == len(parts) - 1 or part['bytes'] == PART_BYTES, 'Short nonfinal part')
    require(sum(part['bytes'] for part in parts) == row['bytes'], 'Part sizes do not sum to full size')


def validate_manifest(manifest):
    require(set(manifest) == {'schema', 'repository', 'tag', 'source_file_count',
                             'build_input_count', 'source_zip', 'jar', 'files'}, 'Unexpected manifest fields')
    require(type(manifest['schema']) is int and manifest['schema'] == 1, 'Unknown manifest schema')
    require(manifest['repository'] == REPOSITORY and manifest['tag'] == TAG, 'Wrong repository/tag')
    require(manifest['source_file_count'] == SOURCE_COUNT, 'Wrong source file count')
    require(manifest['build_input_count'] == BUILD_COUNT, 'Wrong build input count')
    require(manifest['source_zip'] == {'name': ZIP_NAME, 'bytes': ZIP_SIZE, 'sha256': ZIP_SHA},
            'Wrong fixed source ZIP')
    jar = manifest['jar']
    require(set(jar) == {'name', 'bytes', 'sha256', 'parts'}, 'Unexpected JAR fields')
    require(jar['name'] == JAR_NAME and jar['bytes'] == JAR_SIZE and jar['sha256'] == JAR_SHA,
            'Wrong fixed JAR')
    used_parts = set()
    validate_parts(jar, used_parts)
    rows = manifest['files']
    require(isinstance(rows, list) and len(rows) == SOURCE_COUNT, 'Wrong source manifest count')
    paths, split_paths = [], set()
    for row in rows:
        require(set(row) <= {'path', 'bytes', 'sha256', 'git_blob_sha', 'mode', 'input_path', 'parts'}
                and {'path', 'bytes', 'sha256', 'git_blob_sha', 'mode'} <= set(row), 'Unexpected source fields')
        path = row['path']
        safe_relative(path)
        paths.append(path)
        validate_size_hash(row)
        require(check_digest(row['git_blob_sha'], 40), 'Invalid Git blob SHA')
        expected_mode = '100755' if path == 'gradlew' or path.endswith('.sh') else '100644'
        require(row['mode'] == expected_mode, 'Unexpected source mode: ' + path)
        if 'input_path' in row:
            require(path == CI_PATH and row['input_path'] == CI_INPUT and 'parts' not in row,
                    'Unexpected input remapping')
        if 'parts' in row:
            require(path in LARGE_SOURCE_PATHS and 'input_path' not in row, 'Unexpected split source')
            split_paths.add(path)
            validate_parts(row, used_parts)
    require(paths == sorted(paths) and len(paths) == len(set(paths)), 'Source paths unordered or duplicated')
    require(split_paths == LARGE_SOURCE_PATHS, 'Wrong split source archive set')
    ci = next((row for row in rows if row['path'] == CI_PATH), None)
    require(ci is not None and ci.get('input_path') == CI_INPUT, 'Original CI mapping missing')
    path_set = set(paths)
    require(not any('/'.join(PurePosixPath(p).parts[:i]) in path_set
                    for p in paths for i in range(1, len(PurePosixPath(p).parts))), 'File/directory path collision')
    return manifest


def load_manifest(path):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, 'Duplicate JSON key: ' + key)
            result[key] = value
        return result
    data = Path(path).read_bytes()
    require(sha256(data) == MANIFEST_SHA, 'Approved manifest SHA-256 mismatch')
    return validate_manifest(json.loads(data.decode('utf-8'), object_pairs_hook=pairs))


def reconstruct(staging, row):
    chunks = []
    for part in row['parts']:
        data = read_bytes(staging, part['path'], payload=True, expected_size=part['bytes'])
        verify_bytes(data, part, part['path'])
        chunks.append(data)
    data = b''.join(chunks)
    verify_bytes(data, row, row.get('path', row.get('name', 'artifact')))
    return data


def write_new(path, data, mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(data)
    path.chmod(mode)


def verify_source_files(root, manifest, *, exact=False):
    wanted = {row['path'] for row in manifest['files']}
    if exact:
        found = set()
        for path in root.rglob('*'):
            require(not path.is_symlink(), 'Source symlink')
            if path.is_file():
                found.add(path.relative_to(root).as_posix())
            else:
                require(path.is_dir(), 'Source special file')
        require(found == wanted, 'Clean source membership differs from manifest')
    for row in manifest['files']:
        data = read_bytes(root, row['path'], expected_size=row['bytes'])
        verify_bytes(data, row, row['path'])
        mode = stat.S_IMODE((root / row['path']).stat().st_mode)
        require(mode == int(row['mode'][-3:], 8), 'Source mode differs: ' + row['path'])


def verify_build_inputs(root, manifest, jar):
    report = json.loads(read_bytes(root, 'docs/reports/build-inputs.json'))
    require(report.get('plugin_sha256') == JAR_SHA and sha256(jar) == JAR_SHA, 'Build manifest JAR mismatch')
    rows = report.get('files')
    require(isinstance(rows, list) and len(rows) == BUILD_COUNT, 'Expected exactly 155 build inputs')
    available = {row['path'] for row in manifest['files']}
    seen = set()
    for row in rows:
        path = row.get('path')
        require(path in available and path not in seen, 'Missing/duplicate build input')
        seen.add(path)
        verify_bytes(read_bytes(root, path, expected_size=row['bytes']), row, path)


def hydrate(manifest, staging, clean, dist):
    require(not clean.exists() and not dist.exists(), 'Refusing existing hydration output')
    require(not clean.resolve().is_relative_to(staging.resolve())
            and not dist.resolve().is_relative_to(clean.resolve()), 'Outputs must be separate from checkout/source')
    clean.mkdir(parents=True)
    dist.mkdir(parents=True)
    for row in manifest['files']:
        if 'parts' in row:
            data = reconstruct(staging, row)
        else:
            input_path = row.get('input_path', row['path'])
            data = read_bytes(staging, input_path, payload='input_path' in row, expected_size=row['bytes'])
            verify_bytes(data, row, row['path'])
        write_new(checked_path(clean, row['path']), data, int(row['mode'][-3:], 8))
    jar = reconstruct(staging, manifest['jar'])
    write_new(dist / JAR_NAME, jar)
    verify_source_files(clean, manifest, exact=True)
    verify_build_inputs(clean, manifest, jar)
    print('Hydrated exactly 296 source files and verified all 155 build inputs; fixed JAR unchanged.')


def run(command, *, cwd=None, capture=False, data=None):
    result = subprocess.run(command, cwd=cwd, input=data, stdout=subprocess.PIPE if capture else None,
                            stderr=None, check=False)
    require(result.returncode == 0, 'Command failed (' + str(result.returncode) + '): ' + ' '.join(map(str, command[:4])))
    return result.stdout if capture else None


def prepare(manifest, manifest_path, clean, dist, evidence):
    require(not os.environ.get('GH_TOKEN') and not os.environ.get('GITHUB_TOKEN'), 'Token present in offline audit step')
    evidence.mkdir(parents=True, exist_ok=True)
    require(not (evidence / 'prepared.json').exists(), 'Refusing existing prepared evidence')
    verify_source_files(clean, manifest)
    verify_bytes((dist / JAR_NAME).read_bytes(), manifest['jar'], JAR_NAME)
    verify_build_inputs(clean, manifest, (dist / JAR_NAME).read_bytes())
    for script in ('tools/verify_jar.py', 'third-party/sources/verify_sources.py'):
        command = [sys.executable, '-B', str(clean / script)]
        if script.endswith('verify_sources.py'):
            command.append('--plugin-jar')
        command.append(str(dist / JAR_NAME))
        result = run(command, cwd=clean, capture=True)
        write_new(evidence / (Path(script).stem + '.txt'), result)
    run([sys.executable, '-B', str(clean / 'tools/package_source.py'), str(dist / ZIP_NAME)], cwd=clean)
    verify_bytes((dist / ZIP_NAME).read_bytes(), manifest['source_zip'], ZIP_NAME)
    correspondence = run([sys.executable, '-B', str(clean / 'tools/verify_source_handoff.py'),
                          str(dist / ZIP_NAME), str(dist / JAR_NAME)], cwd=clean, capture=True)
    report = json.loads(correspondence)
    require(report['source_file_count'] == SOURCE_COUNT and report['build_bound_inputs'] == BUILD_COUNT,
            'Source correspondence counts differ')
    require(report['plugin_sha256'] == JAR_SHA and report['source_archive_sha256'] == ZIP_SHA,
            'Source correspondence hashes differ')
    write_new(dist / 'SOURCE-CORRESPONDENCE.json', correspondence)
    checksum_names = [JAR_NAME, ZIP_NAME, 'SOURCE-CORRESPONDENCE.json']
    write_new(dist / 'SHA256SUMS', ''.join(sha256((dist / name).read_bytes()) + '  ' + name + '\n'
                                        for name in checksum_names).encode('utf-8'))
    require({path.name for path in dist.iterdir()} == ASSET_NAMES, 'Unexpected local release assets')
    state = {'manifest_sha256': sha256(manifest_path.read_bytes()),
             'assets': {name: {'bytes': (dist / name).stat().st_size,
                               'sha256': sha256((dist / name).read_bytes())} for name in sorted(ASSET_NAMES)}}
    write_new(evidence / 'prepared.json', (json.dumps(state, indent=2) + '\n').encode('utf-8'))
    print('Fixed artifacts, source correspondence, audits and all asset checksums verified.')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, *, binary=False):
        super().__init__()
        self.binary = binary

    def redirect_request(self, request, response, code, message, headers, new_url):
        require(request.get_method() == 'GET', 'Refusing to redirect a GitHub mutation')
        require(self.binary, 'Only binary asset downloads may redirect')
        require(urlsplit(new_url).scheme == 'https', 'Refusing non-HTTPS asset redirect')
        redirected = super().redirect_request(request, response, code, message, headers, new_url)
        if redirected is not None:
            redirected.remove_header('Authorization')
        return redirected


class GitHub:
    def api(self, endpoint, method='GET', payload=None, *, binary=False, upload=None, max_bytes=None):
        require(endpoint.startswith(f'repos/{REPOSITORY}/') and '://' not in endpoint,
                'API destination is not the fixed repository')
        host = 'uploads.github.com' if upload is not None else 'api.github.com'
        headers = {'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'guardianac-fixed-release',
                   'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json'}
        data = None
        if upload is not None:
            require(method == 'POST', 'Upload must be POST')
            headers['Content-Type'] = 'application/octet-stream'
            data = upload.read_bytes()
        elif payload is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(payload).encode('utf-8')
        request = urllib.request.Request('https://' + host + '/' + endpoint,
                                         data=data, headers=headers, method=method)
        # urllib does not copy unredirected headers to a redirected request.
        # A release-asset CDN redirect must never receive the repository token.
        request.add_unredirected_header('Authorization', 'Bearer ' + os.environ['GH_TOKEN'])
        limit = max_bytes if binary else 16 * 1024 * 1024
        require(type(limit) is int and 0 <= limit <= 100 * 1024 * 1024, 'Missing/invalid bounded API response size')
        try:
            with urllib.request.build_opener(SafeRedirect(binary=binary)).open(request, timeout=120) as response:
                result = response.read(limit + 1)
        except urllib.error.HTTPError as error:
            raise GateError(f'GitHub {method} {endpoint} returned HTTP {error.code}; no retry or cleanup') from None
        require(len(result) <= limit, 'GitHub response exceeds expected size')
        return result if binary else json.loads(result)

    def releases(self):
        rows = []
        for page in range(1, 101):
            batch = self.api(f'repos/{REPOSITORY}/releases?per_page=100&page={page}')
            require(isinstance(batch, list), 'Malformed release list')
            rows.extend(batch)
            if len(batch) < 100:
                return rows
        raise GateError('Release pagination exceeded bounded limit')

    def tags(self):
        rows = self.api(f'repos/{REPOSITORY}/git/matching-refs/tags/{TAG}')
        require(isinstance(rows, list), 'Malformed matching refs')
        return [row for row in rows if row.get('ref') == 'refs/tags/' + TAG]


def check_branch(api, staging_sha):
    require(check_digest(staging_sha, 40), 'Invalid staging SHA')
    branch = api.api(f'repos/{REPOSITORY}/git/ref/heads/main')
    require(branch.get('ref') == 'refs/heads/main'
            and branch.get('object', {}).get('type') == 'commit'
            and branch['object'].get('sha') == staging_sha,
            'main changed from staging SHA; stop without updating any ref')


def check_tag(api, commit_sha):
    rows = api.tags()
    require(len(rows) == 1 and rows[0].get('object', {}).get('type') == 'commit'
            and rows[0]['object'].get('sha') == commit_sha,
            'Actual tag reference does not point to exact clean commit')


def require_release_absent(api):
    require(not any(row.get('tag_name') == TAG for row in api.releases()), 'Release already exists; no overwrite/retry')
    require(not api.tags(), 'Tag already exists; no overwrite/retry')


def check_release(release, release_id, clean_commit, *, draft):
    require(release.get('id') == release_id and release.get('tag_name') == TAG,
            'Release identity changed')
    require(release.get('draft') is draft and release.get('prerelease') is True, 'Release flags differ')
    require(release.get('target_commitish') == clean_commit, 'Release target metadata differs')
    assets = release.get('assets', [])
    require(len(assets) == len(ASSET_NAMES) and {item.get('name') for item in assets} == ASSET_NAMES,
            'Release asset set differs')
    require(all(item.get('state') == 'uploaded' for item in assets), 'Release asset upload incomplete')


def record(evidence, state, **updates):
    state.update(updates)
    # Local evidence is updated, never remote resources. Print immediately because
    # the runner can be lost and a mutation timeout does not prove nonexecution.
    (evidence / 'publication-state.json').write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(updates, sort_keys=True), flush=True)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as stream:
            for name, value in updates.items():
                stream.write(f'- {name}: `{value}`\n')
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a', encoding='utf-8') as stream:
            for name in ('clean_commit', 'release_id', 'release_url', 'staging_sha'):
                if name in updates:
                    stream.write(f'{name}={updates[name]}\n')


def create_clean_commit(api, manifest, clean, staging_sha, evidence, state):
    check_branch(api, staging_sha)
    for row in manifest['files']:
        if 'parts' not in row:
            continue
        data = read_bytes(clean, row['path'], expected_size=row['bytes'])
        verify_bytes(data, row, row['path'])
        blob = api.api(f'repos/{REPOSITORY}/git/blobs', 'POST',
                       {'encoding': 'base64', 'content': base64.b64encode(data).decode('ascii')})
        require(blob.get('sha') == row['git_blob_sha'], 'Created Git blob SHA mismatch')
        record(evidence, state, last_created_blob=blob['sha'])
    entries = [{'path': row['path'], 'mode': row['mode'], 'type': 'blob', 'sha': row['git_blob_sha']}
               for row in manifest['files']]
    tree = api.api(f'repos/{REPOSITORY}/git/trees', 'POST', {'tree': entries})
    tree_sha = tree.get('sha')
    require(tree_sha == EXPECTED_TREE_SHA, 'Created tree differs from approved clean Git tree SHA')
    record(evidence, state, clean_tree=tree_sha)
    remote = api.api(f'repos/{REPOSITORY}/git/trees/{tree_sha}?recursive=1')
    require(remote.get('sha') == EXPECTED_TREE_SHA and remote.get('truncated') is False,
            'Wrong/truncated source tree')
    actual = [{key: row[key] for key in ('path', 'mode', 'type', 'sha')}
              for row in remote['tree'] if row['type'] != 'tree']
    require(sorted(actual, key=lambda row: row['path']) == entries, 'Remote source tree differs from full fixed manifest')
    check_branch(api, staging_sha)
    commit = api.api(f'repos/{REPOSITORY}/git/commits', 'POST',
                     {'message': 'Restore exact public source for GuardianAC Ultimate 1.0.0-dev.1',
                      'tree': tree_sha, 'parents': [staging_sha]})
    clean_commit = commit.get('sha')
    require(check_digest(clean_commit, 40), 'Invalid created commit SHA')
    record(evidence, state, clean_commit=clean_commit, phase='clean-commit-created')
    verified = api.api(f'repos/{REPOSITORY}/git/commits/{clean_commit}')
    require(verified.get('tree', {}).get('sha') == tree_sha
            and [parent.get('sha') for parent in verified.get('parents', [])] == [staging_sha],
            'Clean commit parent/tree mismatch')
    record(evidence, state, phase='clean-commit-verified')
    return clean_commit


def create_tag(api, clean_commit, staging_sha):
    check_branch(api, staging_sha)
    require_release_absent(api)
    result = api.api(f'repos/{REPOSITORY}/git/refs', 'POST',
                    {'ref': 'refs/tags/' + TAG, 'sha': clean_commit})
    require(result.get('ref') == 'refs/tags/' + TAG
            and result.get('object', {}).get('type') == 'commit'
            and result['object'].get('sha') == clean_commit, 'Created tag response differs')
    check_tag(api, clean_commit)


def verify_downloaded_assets(api, release, dist, destination, prepared):
    destination.mkdir(parents=True, exist_ok=False)
    for asset in release['assets']:
        name = asset['name']
        require(type(asset.get('id')) is int, 'Missing asset ID')
        data = api.api(f"repos/{REPOSITORY}/releases/assets/{asset['id']}", binary=True,
                       max_bytes=prepared['assets'][name]['bytes'])
        write_new(destination / name, data)
        verify_bytes(data, prepared['assets'][name], 'downloaded ' + name)
        require(data == (dist / name).read_bytes(), 'Downloaded bytes differ: ' + name)


def publish(manifest, manifest_path, staging, clean, dist, evidence, staging_sha, api=None):
    require(os.environ.get('GITHUB_RUN_ATTEMPT') == '1', 'Only the first attempt is allowed; inspect partial evidence')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Wrong runtime repository')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Wrong runtime branch')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'push', 'Only staging push is allowed')
    require(os.environ.get('GH_TOKEN'), 'Missing scoped publication token')
    require(not (evidence / 'publication-state.json').exists(), 'Existing publication evidence; do not retry')
    prepared = json.loads((evidence / 'prepared.json').read_text(encoding='utf-8'))
    require(prepared['manifest_sha256'] == sha256(manifest_path.read_bytes()), 'Manifest changed after preparation')
    require(set(prepared['assets']) == ASSET_NAMES, 'Prepared asset set differs')
    verify_source_files(clean, manifest)
    verify_build_inputs(clean, manifest, (dist / JAR_NAME).read_bytes())
    require({path.name for path in dist.iterdir()} == ASSET_NAMES, 'Unexpected asset files')
    for name in ASSET_NAMES:
        verify_bytes((dist / name).read_bytes(), prepared['assets'][name], name)
    verify_bytes((dist / JAR_NAME).read_bytes(), manifest['jar'], JAR_NAME)
    verify_bytes((dist / ZIP_NAME).read_bytes(), manifest['source_zip'], ZIP_NAME)
    api = api or GitHub()
    state = {}
    record(evidence, state, staging_sha=staging_sha, phase='preflight', tag=TAG)
    try:
        check_branch(api, staging_sha)
        require_release_absent(api)
        clean_commit = create_clean_commit(api, manifest, clean, staging_sha, evidence, state)
        create_tag(api, clean_commit, staging_sha)
        record(evidence, state, phase='tag-created')
        check_branch(api, staging_sha)
        check_tag(api, clean_commit)
        body = checked_path(staging, 'release-input/RELEASE-BODY.md', payload=True)
        require(body.is_file(), 'Missing reviewed release body')
        record(evidence, state, phase='creating-draft')
        # --verify-tag prevents CLI's implicit tag creation. The API ref checks,
        # not --target/target_commitish metadata, establish the actual tag SHA.
        run(['gh', 'release', 'create', TAG, '--repo', REPOSITORY, '--verify-tag',
             '--target', clean_commit, '--title', 'GuardianAC Ultimate 1.0.0-dev.1 (Pre-release)',
             '--draft', '--prerelease', '--latest=false', '--notes-file', str(body)])
        matches = [row for row in api.releases() if row.get('tag_name') == TAG]
        require(len(matches) == 1, 'Cannot identify exactly one created draft; inspect without retry')
        release = matches[0]
        release_id = release.get('id')
        require(type(release_id) is int and release.get('draft') is True
                and release.get('prerelease') is True and release.get('target_commitish') == clean_commit
                and release.get('assets') == [], 'Unexpected created draft')
        record(evidence, state, release_id=release_id, release_url=release.get('html_url'), phase='draft-created')
        for name in sorted(ASSET_NAMES):
            result = api.api(f'repos/{REPOSITORY}/releases/{release_id}/assets?name={quote(name, safe="")}',
                             'POST', upload=dist / name)
            require(result.get('name') == name and result.get('state') == 'uploaded'
                    and result.get('size') == prepared['assets'][name]['bytes'], 'Asset upload response differs')
            record(evidence, state, last_uploaded_asset=name)
        release = api.api(f'repos/{REPOSITORY}/releases/{release_id}')
        check_release(release, release_id, clean_commit, draft=True)
        verify_downloaded_assets(api, release, dist, evidence / 'downloaded-draft', prepared)
        record(evidence, state, phase='draft-downloads-verified')
        check_branch(api, staging_sha)
        check_tag(api, clean_commit)
        # Re-read the immutable release ID immediately before the sole publish
        # mutation. Another writer changing a mutable ref is detected, never fixed.
        check_release(api.api(f'repos/{REPOSITORY}/releases/{release_id}'), release_id, clean_commit, draft=True)
        record(evidence, state, phase='publishing')
        published = api.api(f'repos/{REPOSITORY}/releases/{release_id}', 'PATCH',
                            {'draft': False, 'prerelease': True, 'make_latest': 'false'})
        check_release(published, release_id, clean_commit, draft=False)
        check_tag(api, clean_commit)
        check_branch(api, staging_sha)
        published = api.api(f'repos/{REPOSITORY}/releases/{release_id}')
        check_release(published, release_id, clean_commit, draft=False)
        verify_downloaded_assets(api, published, dist, evidence / 'downloaded-published', prepared)
        check_tag(api, clean_commit)
        check_branch(api, staging_sha)
        record(evidence, state, phase='published-and-downloads-verified', release_url=published['html_url'])
        print('main remains at staging_sha. The coordinator must independently verify and CAS main to clean_commit.', flush=True)
    except BaseException as error:
        record(evidence, state, phase='stopped-requires-inspection', error=str(error)[:1000])
        # Read-only discovery can recover an ID after uncertain CLI creation;
        # never repeat create/upload/publish or delete a partial resource.
        if 'release_id' not in state:
            try:
                matches = [row for row in api.releases() if row.get('tag_name') == TAG]
                record(evidence, state, observed_release_ids=[row.get('id') for row in matches])
            except Exception as lookup_error:
                print('Read-only evidence lookup failed: ' + str(lookup_error), file=sys.stderr)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('hydrate', 'prepare', 'publish'))
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--staging', type=Path, required=True)
    parser.add_argument('--clean', type=Path, required=True)
    parser.add_argument('--dist', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--staging-sha', default=os.environ.get('GITHUB_SHA'))
    args = parser.parse_args()
    for name in ('manifest', 'staging', 'clean', 'dist', 'evidence'):
        setattr(args, name, getattr(args, name).absolute())
    manifest = load_manifest(args.manifest)
    if args.phase != 'publish':
        require(not os.environ.get('GH_TOKEN') and not os.environ.get('GITHUB_TOKEN'), 'Token exposed to offline phase')
    if args.phase == 'hydrate':
        hydrate(manifest, args.staging, args.clean, args.dist)
    elif args.phase == 'prepare':
        prepare(manifest, args.manifest, args.clean, args.dist, args.evidence)
    else:
        publish(manifest, args.manifest, args.staging, args.clean, args.dist, args.evidence, args.staging_sha)


if __name__ == '__main__':
    main()
