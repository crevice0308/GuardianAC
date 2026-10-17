#!/usr/bin/env python3
"""Read final classfiles offline; never load a plugin, start a server, or use the network."""
import argparse
import json
import struct
import zipfile
from pathlib import Path

BUILDER = 'dev/guardianac/libs/pe/impl/packetevents/factory/spigot/SpigotPacketEventsBuilder'
ADAPTER = 'dev/guardianac/packet/PacketAdapter'
BANNED = ('/bstats/', '.bstats.', 'bstats.org')
SCHEDULERS = ('java/util/concurrent/Scheduled', 'java/util/concurrent/Executors',
              'java/util/Timer')


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, count):
        if count < 0 or self.offset + count > len(self.data):
            raise ValueError('Truncated classfile')
        value = self.data[self.offset:self.offset + count]
        self.offset += count
        return value

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H', self.take(2))[0]
    def u4(self): return struct.unpack('>I', self.take(4))[0]


def classfile(data):
    reader = Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError('Invalid classfile magic')
    reader.take(4)
    pool, count = [None], reader.u2()
    while len(pool) < count:
        tag = reader.u1()
        if tag == 1:
            # Relevant names/URLs are ASCII, unchanged by modified UTF-8.
            value = reader.take(reader.u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4): value = reader.take(4)
        elif tag in (5, 6): value = reader.take(8)
        elif tag in (7, 8, 16, 19, 20): value = reader.u2()
        elif tag in (9, 10, 11, 12, 17, 18): value = (reader.u2(), reader.u2())
        elif tag == 15: value = (reader.u1(), reader.u2())
        else: raise ValueError('Unknown constant-pool tag: ' + str(tag))
        pool.append((tag, value))
        if tag in (5, 6): pool.append(None)

    def utf(index):
        if pool[index][0] != 1: raise ValueError('Expected UTF-8 constant')
        return pool[index][1]

    def attributes():
        return [(utf(reader.u2()), reader.take(reader.u4())) for _ in range(reader.u2())]

    reader.take(6)
    reader.take(reader.u2() * 2)
    methods = []
    for method_section in (False, True):
        for _ in range(reader.u2()):
            reader.u2()
            name, descriptor = utf(reader.u2()), utf(reader.u2())
            attrs = attributes()
            if method_section:
                code = next((v for k, v in attrs if k == 'Code'), None)
                if code is not None:
                    body = Reader(code)
                    body.take(4)
                    code = body.take(body.u4())
                methods.append((name, descriptor, code))
    attributes()
    if reader.offset != len(data): raise ValueError('Trailing classfile bytes')
    return pool, methods


def method_reference(pool, index):
    tag, value = pool[index]
    if tag not in (10, 11):
        raise ValueError('Invocation has no method reference')
    owner_index, signature_index = value
    owner = pool[pool[owner_index][1]][1]
    name_index, descriptor_index = pool[signature_index][1]
    return owner, pool[name_index][1], pool[descriptor_index][1]


def instructions(code):
    """Walk actual instructions, respecting switch alignment and wide operands."""
    position = 0
    one = set([0x10, 0x12, 0xa9, 0xbc] + list(range(0x15, 0x1a)) + list(range(0x36, 0x3b)))
    two = set([0x11, 0x13, 0x14, 0x84, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7]
              + list(range(0x99, 0xa9)) + list(range(0xb2, 0xb9)))
    while position < len(code):
        start, opcode = position, code[position]
        position += 1
        if opcode in (0xaa, 0xab):
            position = (position + 3) & ~3
            if position + 8 > len(code): raise ValueError('Truncated switch')
            if opcode == 0xaa:
                if position + 12 > len(code): raise ValueError('Truncated tableswitch')
                low, high = struct.unpack('>ii', code[position + 4:position + 12])
                count = high - low + 1
                if count < 0: raise ValueError('Invalid tableswitch')
                position += 12 + 4 * count
            else:
                count = struct.unpack('>i', code[position + 4:position + 8])[0]
                if count < 0: raise ValueError('Invalid lookupswitch')
                position += 8 + 8 * count
        elif opcode == 0xc4:
            if position >= len(code): raise ValueError('Truncated wide')
            position += 5 if code[position] == 0x84 else 3
        elif opcode in one: position += 1
        elif opcode in two: position += 2
        elif opcode == 0xc5: position += 3
        elif opcode in (0xb9, 0xba, 0xc8, 0xc9): position += 4
        elif opcode > 0xc9: raise ValueError('Invalid opcode at ' + str(start))
        if position > len(code): raise ValueError('Truncated bytecode instruction')
        yield start, opcode, code[start + 1:position]


def invocation_sites(code, pool):
    for start, opcode, operand in instructions(code):
        if opcode in (0xb6, 0xb7, 0xb8, 0xb9):
            yield start, opcode, method_reference(pool, struct.unpack('>H', operand[:2])[0])


def invocations(code, pool):
    return (reference for _, _, reference in invocation_sites(code, pool))


def inspect(jar_path):
    issues, parsed, classes = [], {}, 0
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)): issues.append('Duplicate ZIP members')
        for name in names:
            if any(word in name.lower() for word in BANNED):
                issues.append('Forbidden bStats archive member: ' + name)
            if not name.endswith('.class'): continue
            classes += 1
            pool, methods = classfile(archive.read(name))
            constants = [entry[1] for entry in pool if entry and entry[0] == 1]
            if any(word in value.lower() for value in constants for word in BANNED):
                issues.append('Forbidden bStats class reference or endpoint: ' + name)
            if name.startswith(BUILDER) or name == ADAPTER + '.class':
                parsed[name] = (pool, methods)
        expected = {BUILDER + '.class', BUILDER + '$1.class'}
        if {n for n in parsed if n.startswith(BUILDER)} != expected:
            issues.append('Unexpected embedded builder class set')
        builder_calls, init_calls = [], []
        for name in expected & parsed.keys():
            pool, methods = parsed[name]
            for method_name, _, code in methods:
                if code is None: continue
                calls = list(invocations(code, pool))
                builder_calls.extend(calls)
                if name == BUILDER + '$1.class' and method_name == 'init': init_calls = calls
                for owner, target, _ in calls:
                    if (any(word in owner.lower() for word in BANNED)
                            or owner.startswith(SCHEDULERS) or target in ('scheduleAtFixedRate', 'scheduleWithFixedDelay')):
                        issues.append('Metrics/scheduler invocation in builder method: ' + method_name + ' -> ' + owner + '.' + target)
        if not init_calls: issues.append('Embedded builder init() bytecode missing')
        if not any(owner == 'org/bukkit/plugin/PluginManager' and name == 'registerEvents'
                   for owner, name, _ in init_calls):
            issues.append('Embedded builder init() is not the expected functional lifecycle')
        adapter = parsed.get(ADAPTER + '.class')
        if not adapter:
            issues.append('PacketAdapter class missing')
        else:
            pool, methods = adapter
            load = next((code for name, _, code in methods if name == 'load'), None)
            if load is None: issues.append('PacketAdapter load() bytecode missing')
            else:
                calls = list(invocations(load, pool))
                if any(name == 'bStats' for _, name, _ in calls):
                    issues.append('PacketAdapter still relies on deprecated no-op bStats setting')
                if not any(owner == BUILDER and name == 'build' for owner, name, _ in calls):
                    issues.append('PacketAdapter does not use the privately embedded builder')
                code_instructions = list(instructions(load))
                sites = list(invocation_sites(load, pool))
                update_calls = [(offset, owner, descriptor) for offset, _, (owner, name, descriptor) in sites
                                if name == 'checkForUpdates']
                load_calls = [offset for offset, _, (owner, name, _) in sites
                              if owner == 'dev/guardianac/libs/pe/api/packetevents/PacketEventsAPI' and name == 'load']
                disabled = False
                if len(update_calls) == 1 and len(load_calls) == 1:
                    offset, owner, descriptor = update_calls[0]
                    instruction_index = next(i for i, instruction in enumerate(code_instructions) if instruction[0] == offset)
                    disabled = (owner == 'dev/guardianac/libs/pe/api/packetevents/settings/PacketEventsSettings'
                                and descriptor == '(Z)L' + owner + ';' and instruction_index > 0
                                and code_instructions[instruction_index - 1][1] == 0x03
                                and offset < load_calls[0])
                if not disabled:
                    issues.append('PacketAdapter must call checkForUpdates(false) before API load()')
    return {'file': Path(jar_path).name, 'classfiles_scanned': classes,
            'builder_methods_invocations_scanned': len(builder_calls),
            'builder_init_invocations_scanned': len(init_calls),
            'bstats_classes_references_and_endpoints': 'absent' if not issues else 'FAILED',
            'mode': 'offline classfile inspection; no classloading, server, or network', 'issues': issues}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    result = inspect(args.jar)
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result['issues']))


if __name__ == '__main__': main()
