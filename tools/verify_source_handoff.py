#!/usr/bin/env python3
"""Verify a frozen source ZIP against this tree and its exact plugin build manifest.

Read-only, offline and no archive extraction. This proves byte correspondence,
not independent reproducibility, license certification or runtime correctness.
"""
import argparse
import hashlib
import json
from pathlib import Path
import stat
import struct
import zipfile
import zlib

import package_source

PREFIX = 'GuardianAC-Ultimate/'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_container(archive, entries):
    """Require the compact seekable ZIP layout produced by package_source.py.

    ZIP comments, extra fields, descriptors, split/ZIP64 containers and otherwise
    ignored prefix/trailer/gap bytes are not part of this bounded handoff format.
    """
    if archive.comment or not entries or len(entries) >= 65535:
        raise ValueError('Unexpected archive comment or member count')
    stream = archive.fp
    stream.seek(0, 2)
    size = stream.tell()
    if size < 22:
        raise ValueError('Missing archive directory terminator')
    stream.seek(size - 22)
    end = struct.unpack('<4s4H2LH', stream.read(22))
    signature, disk, directory_disk, disk_count, count, directory_size, directory_offset, comment = end
    if (signature != b'PK\x05\x06' or disk or directory_disk or comment
            or disk_count != len(entries) or count != len(entries)
            or directory_offset + directory_size != size - 22
            or directory_offset != archive.start_dir):
        raise ValueError('Unexpected archive prefix, trailer or directory layout')
    offset = central_size = 0
    for entry in entries:
        if entry.extra or entry.comment or entry.flag_bits & ~0x800:
            raise ValueError('Unexpected member extra fields, comments or flags')
        encoded_name = entry.filename.encode('utf-8' if entry.flag_bits & 0x800 else 'ascii')
        if entry.header_offset != offset:
            raise ValueError('Unexpected archive prefix or gap')
        stream.seek(offset)
        header = stream.read(30)
        if len(header) != 30:
            raise ValueError('Truncated local header')
        sig, version, flags, compression, clock, date, crc, compressed, plain, name_len, extra_len = struct.unpack('<4s5H3L2H', header)
        if (sig != b'PK\x03\x04' or version != 20 or flags != entry.flag_bits
                or compression != entry.compress_type or clock != 0 or date != 33
                or crc != entry.CRC or compressed != entry.compress_size
                or plain != entry.file_size or name_len != len(encoded_name)
                or extra_len or stream.read(name_len) != encoded_name):
            raise ValueError('Local header differs from deterministic member metadata')
        offset += 30 + len(encoded_name) + entry.compress_size
        central_size += 46 + len(encoded_name)
    if offset != directory_offset or central_size != directory_size:
        raise ValueError('Unaccounted archive data or directory fields')


def verify_entries(archive, expected):
    """Compare all ZIP members with a nonempty mapping of relative path to bytes."""
    if not expected:
        raise ValueError('Empty source selection')
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    wanted = {PREFIX + path for path in expected}
    if len(names) != len(set(names)):
        raise ValueError('Duplicate archive member')
    if set(names) != wanted:
        raise ValueError('Archive member set differs from selected source tree')
    if names != [PREFIX + path for path in expected]:
        raise ValueError('Archive member order differs from deterministic source selection')
    verify_container(archive, entries)
    files = []
    for entry in entries:
        relative = entry.filename[len(PREFIX):]
        package_source.safe_relative(Path(relative))
        expected_mode = 0o755 if relative == 'gradlew' or relative.endswith('.sh') else 0o644
        mode = entry.external_attr >> 16
        if (entry.is_dir() or not stat.S_ISREG(mode)
                or stat.S_IMODE(mode) != expected_mode or entry.create_system != 3
                or entry.external_attr != (stat.S_IFREG | expected_mode) << 16
                or entry.internal_attr != 0 or entry.volume != 0
                or entry.create_version != 20 or entry.extract_version != 20 or entry.reserved != 0
                or entry.flag_bits & 1 or entry.compress_type != zipfile.ZIP_DEFLATED
                or entry.date_time != (1980, 1, 1, 0, 0, 0)):
            raise ValueError('Unexpected member metadata: ' + relative)
        # Reject declared oversized members before decompression. The expected
        # local content fixes both the maximum size and exact final bytes.
        wanted_data = expected[relative]
        if entry.file_size != len(wanted_data):
            raise ValueError('Member size mismatch: ' + relative)
        encoded_name = entry.filename.encode('utf-8' if entry.flag_bits & 0x800 else 'ascii')
        archive.fp.seek(entry.header_offset + 30 + len(encoded_name))
        compressed = archive.fp.read(entry.compress_size)
        if len(compressed) != entry.compress_size:
            raise ValueError('Truncated member compressed data: ' + relative)
        decoder = zlib.decompressobj(-15)
        data = decoder.decompress(compressed, len(wanted_data) + 1)
        # ZipFile.read() accepts junk after the raw-deflate EOF inside the
        # declared compressed span. Do not silently carry such undeclared data.
        if (not decoder.eof or decoder.unused_data or decoder.unconsumed_tail
                or len(data) != len(wanted_data)
                or zlib.crc32(data) & 0xffffffff != entry.CRC):
            raise ValueError('Malformed or trailing compressed member data: ' + relative)
        if data != wanted_data:
            raise ValueError('Member content mismatch: ' + relative)
        files.append({'path': relative, 'bytes': len(data), 'sha256': digest(data)})
    return sorted(files, key=lambda row: row['path'])


def verify_build_inputs(expected, jar_bytes):
    manifest = json.loads(expected['docs/reports/build-inputs.json'])
    jar_hash = digest(jar_bytes)
    if manifest.get('plugin_sha256') != jar_hash:
        raise ValueError('Plugin artifact differs from the build-input manifest')
    rows = manifest.get('files')
    if not isinstance(rows, list) or not rows:
        raise ValueError('Build-input manifest must be nonempty')
    seen = set()
    for row in rows:
        path = row.get('path')
        if not isinstance(path, str) or path in seen or path not in expected:
            raise ValueError('Missing or duplicate build-input path')
        seen.add(path)
        data = expected[path]
        if (type(row.get('bytes')) is not int or row['bytes'] != len(data)
                or row.get('sha256') != digest(data)):
            raise ValueError('Build-input content differs: ' + path)
    return jar_hash, len(rows)


def verify(root, source_zip, plugin_jar):
    expected = {relative.as_posix(): path.read_bytes()
                for path, relative in package_source.sources(root)}
    jar_hash, input_count = verify_build_inputs(expected, plugin_jar.read_bytes())
    with zipfile.ZipFile(source_zip) as archive:
        files = verify_entries(archive, expected)
    return {
        'status': 'verified', 'source_archive': source_zip.name,
        'source_archive_sha256': digest(source_zip.read_bytes()),
        'plugin_artifact': plugin_jar.name, 'plugin_sha256': jar_hash,
        'source_file_count': len(files), 'build_bound_inputs': input_count,
        'scope': 'Exact source ZIP/tree/build-input byte correspondence; no extraction, build, server, network or publication.',
        'limits': ['Not an independent rebuild or runtime test.',
                   'Dependency source and plugin class audits are separate checks.'],
        'files': files,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_zip', type=Path)
    parser.add_argument('plugin_jar', type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(Path(__file__).resolve().parent.parent,
                            args.source_zip, args.plugin_jar), indent=2))


if __name__ == '__main__':
    main()
