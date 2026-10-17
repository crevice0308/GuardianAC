#!/usr/bin/env python3
"""Inspect local official server bytecode without Java, sockets, or jar execution.

Usage: python -B audit_server_statistics.py /path/to/paper-26.3 /path/to/purpur-26.3
Only sanitized hashes, public bytecode locations, and proof results are emitted.
"""
from pathlib import Path
import json
import struct
import sys
import zipfile

from fixture_privacy import inspect_reviewed_server


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, count):
        value = self.data[self.offset:self.offset + count]
        if len(value) != count:
            raise ValueError('Truncated class file')
        self.offset += count
        return value

    def integer(self, width):
        return int.from_bytes(self.take(width), 'big')


class ClassFile:
    """Small read-only class-file parser. It never loads or executes JVM code."""
    def __init__(self, data):
        reader = Reader(data)
        if reader.integer(4) != 0xCAFEBABE:
            raise ValueError('Invalid class-file magic')
        reader.take(4)  # minor, major
        count = reader.integer(2)
        self.pool = [None]
        while len(self.pool) < count:
            tag = reader.integer(1)
            if tag == 1:
                item = (tag, reader.take(reader.integer(2)).decode('utf-8', errors='replace'))
            elif tag in (3, 4, 5, 6):
                formats = {3: '>i', 4: '>f', 5: '>q', 6: '>d'}
                item = (tag, struct.unpack(formats[tag], reader.take(8 if tag in (5, 6) else 4))[0])
            elif tag in (7, 8, 16, 19, 20):
                item = (tag, reader.integer(2))
            elif tag in (9, 10, 11, 12, 17, 18):
                item = (tag, reader.integer(2), reader.integer(2))
            elif tag == 15:
                item = (tag, reader.integer(1), reader.integer(2))
            else:
                raise ValueError('Unsupported constant-pool tag')
            self.pool.append(item)
            if tag in (5, 6):
                self.pool.append(None)
        reader.take(6)  # access, this, super
        reader.take(2 * reader.integer(2))
        for _ in range(reader.integer(2)):
            reader.take(6)
            self.attributes(reader)
        self.methods = {}
        for _ in range(reader.integer(2)):
            reader.take(2)
            name = self.value(reader.integer(2))
            descriptor = self.value(reader.integer(2))
            attributes = self.attributes(reader)
            if 'Code' in attributes:
                code = Reader(attributes['Code'])
                code.take(4)  # max_stack, max_locals
                self.methods[name + descriptor] = code.take(code.integer(4))
        self.attributes(reader)
        if reader.offset != len(data):
            raise ValueError('Unexpected trailing class bytes')

    def attributes(self, reader):
        result = {}
        for _ in range(reader.integer(2)):
            name = self.value(reader.integer(2))
            value = reader.take(reader.integer(4))
            if name in result:
                raise ValueError('Duplicate class attribute')
            result[name] = value
        return result

    def value(self, index):
        item = self.pool[index]
        if item[0] in (1, 3, 4, 5, 6):
            return item[1]
        if item[0] in (7, 8, 16, 19, 20):
            return self.value(item[1])
        if item[0] == 12:
            return self.value(item[1]) + self.value(item[2])
        if item[0] in (9, 10, 11):
            return self.value(item[1]) + '.' + self.value(item[2])
        raise ValueError('Unsupported constant-pool reference')

    def expect_reference(self, code, offset, opcode, expected):
        require(code[offset] == opcode, 'Unexpected bytecode opcode at ' + str(offset))
        width = 1 if opcode == 0x12 else 2
        index = int.from_bytes(code[offset + 1:offset + 1 + width], 'big')
        require(self.value(index) == expected, 'Unexpected public bytecode reference at ' + str(offset))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def verify_disabled_metrics_gate(metrics_gate):
    code = metrics_gate.methods['startMetrics()V']
    metrics_gate.expect_reference(code, 14, 0x12, 'plugins')
    metrics_gate.expect_reference(code, 22, 0x12, 'bStats')
    metrics_gate.expect_reference(code, 27, 0x12, 'config.yml')
    metrics_gate.expect_reference(code, 118, 0x12, 'enabled')
    require(code[120] == 0x04, 'Expected default-true boolean argument')
    metrics_gate.expect_reference(code, 121, 0xB6,
        'org/bukkit/configuration/file/YamlConfiguration.getBoolean(Ljava/lang/String;Z)Z')
    require(code[124] == 0x99, 'Expected ifeq disabled guard')
    target = 124 + struct.unpack('>h', code[125:127])[0]
    require(target == len(code) - 1 and code[target] == 0xB1,
            'Disabled branch must jump directly to the final return')
    metrics_gate.expect_reference(code, 127, 0xBB, 'com/destroystokyo/paper/Metrics')
    metrics_gate.expect_reference(code, 138, 0xB7,
        'com/destroystokyo/paper/Metrics.<init>(Ljava/lang/String;Ljava/lang/String;ZLjava/util/logging/Logger;)V')
    return {'config_path': 'plugins/bStats/config.yml', 'enabled_getBoolean_offset': 121,
            'default_if_missing': True, 'disabled_ifeq_offset': 124, 'disabled_return_offset': target,
            'metrics_new_offset': 127, 'metrics_constructor_offset': 138,
            'disabled_returns_before_metrics_construction': True}


def verify_metrics_sender(metrics):
    constructor = metrics.methods['<init>(Ljava/lang/String;Ljava/lang/String;ZLjava/util/logging/Logger;)V']
    metrics.expect_reference(constructor, 6, 0xB8,
        'java/util/concurrent/Executors.newScheduledThreadPool(I)Ljava/util/concurrent/ScheduledExecutorService;')
    metrics.expect_reference(constructor, 43, 0xB6, 'com/destroystokyo/paper/Metrics.startSubmitting()V')
    schedule = metrics.methods['startSubmitting()V']
    for offset, value in ((7, 60000.0), (10, 3.0), (16, 3.0), (24, 60000.0), (30, 30.0), (62, 1800000)):
        metrics.expect_reference(schedule, offset, 0x14, value)
    metrics.expect_reference(schedule, 47, 0xB9,
        'java/util/concurrent/ScheduledExecutorService.schedule(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
    metrics.expect_reference(schedule, 68, 0xB9,
        'java/util/concurrent/ScheduledExecutorService.scheduleAtFixedRate(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
    sender = metrics.methods['sendData(Lorg/json/simple/JSONObject;)V']
    endpoint = 'https://bstats.org/submitData/server-implementation'
    metrics.expect_reference(sender, 18, 0x12, endpoint)
    metrics.expect_reference(sender, 23, 0xB6, 'java/net/URL.openConnection()Ljava/net/URLConnection;')
    metrics.expect_reference(sender, 39, 0x12, 'POST')
    metrics.expect_reference(sender, 63, 0x12, 'gzip')
    return {'metrics_constructor_scheduler_factory_offset': 6,
            'metrics_constructor_start_submitting_offset': 43,
            'first_send_delay_ms': {'minimum': 180000, 'maximum_exclusive': 360000},
            'second_send_extra_delay_ms': {'minimum': 0, 'maximum_exclusive': 1800000},
            'repeat_interval_ms': 1800000, 'endpoint': endpoint, 'method': 'POST', 'encoding': 'gzip',
            'payload_categories': ['persistent server metrics identifier (value deliberately omitted)',
                'OS name, architecture, OS version, processor count',
                'server brand and Minecraft/server implementation version',
                'online player count, online/offline mode, Java version, legacy plugin counts']}


def verify_disabled_spark_gate(spark):
    code = spark.methods['enable()V']
    spark.expect_reference(code, 13, 0xB4, 'io/papermc/paper/configuration/GlobalConfiguration$Spark.enabledZ')
    require(code[16] == 0x99 and 16 + struct.unpack('>h', code[17:19])[0] == 36,
            'Expected spark disabled branch')
    spark.expect_reference(code, 28, 0xB9, 'me/lucko/spark/paper/api/PaperSparkModule.enable()V')
    require(code[60] == 0xB1 and len(code) == 61, 'Expected spark final return')
    return {'config_path': 'config/paper-global.yml', 'enabled_field_offset': 13,
            'disabled_ifeq_offset': 16, 'disabled_target_offset': 36, 'module_enable_offset': 28,
            'scope': 'Disabled branch skips module.enable; construction is not claimed to be absent.'}


def verify_startup_network_gates(server_class, variant):
    code = server_class.methods['runServer()V']
    if variant == 'paper-26.3':
        server_class.expect_reference(code, 209, 0x13, 'paper.disableStartupVersionCheck')
        server_class.expect_reference(code, 212, 0xB8, 'java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;')
        require(code[215] == 0xC7 and 215 + struct.unpack('>h', code[216:218])[0] == 242,
                'Expected startup property bypass before scheduling')
        server_class.expect_reference(code, 224, 0xB4,
            'io/papermc/paper/configuration/GlobalConfiguration$UpdateChecker.enabledZ')
        require(code[227] == 0x99 and 227 + struct.unpack('>h', code[228:230])[0] == 242,
                'Expected update-checker false bypass before scheduling')
        server_class.expect_reference(code, 238, 0xB8,
            'java/util/concurrent/CompletableFuture.runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;')
        return {'property': 'paper.disableStartupVersionCheck', 'config_enabled_offset': 224,
                'property_ifnonnull_offset': 215, 'config_ifeq_offset': 227,
                'disabled_target_offset': 242, 'run_async_offset': 238,
                'disabled_skips_startup_update_task_scheduling': True}
    server_class.expect_reference(code, 209, 0x13, 'Purpur.IReallyDontWantStartupCommands')
    server_class.expect_reference(code, 212, 0xB8, 'java/lang/Boolean.getBoolean(Ljava/lang/String;)Z')
    require(code[215] == 0x9A and 215 + struct.unpack('>h', code[216:218])[0] == 298,
            'Expected Purpur startup-command bypass')
    server_class.expect_reference(code, 218, 0xB2, 'org/purpurmc/purpur/PurpurConfig.startupCommandsLjava/util/List;')
    require(not any(item and item[0] == 1 and item[1] == 'getUpdateStatusStartupMessage'
                    for item in server_class.pool), 'Unexpected Paper startup-checker reference in Purpur')
    return {'property': 'Purpur.IReallyDontWantStartupCommands', 'property_ifne_offset': 215,
            'disabled_target_offset': 298, 'startup_commands_field_offset': 218,
            'disabled_skips_startup_commands': True,
            'paper_startup_update_method_reference_absent_in_pinned_server': True}


def audit(directory):
    directory = Path(directory)
    server = inspect_reviewed_server(directory)
    runtime = directory / ('versions/26.3/' + directory.name + '.jar')
    with zipfile.ZipFile(runtime) as archive:
        gate = ClassFile(archive.read('com/destroystokyo/paper/Metrics$PaperMetrics.class'))
        metrics = ClassFile(archive.read('com/destroystokyo/paper/Metrics.class'))
        spark = ClassFile(archive.read('io/papermc/paper/SparksFly.class'))
        server_class = ClassFile(archive.read('net/minecraft/server/MinecraftServer.class'))
    return {'kind': 'offline_exact_bytecode_audit', 'server': server,
            'statistics_gate': verify_disabled_metrics_gate(gate),
            'sender': verify_metrics_sender(metrics), 'spark_gate': verify_disabled_spark_gate(spark),
            'startup_network_gates': verify_startup_network_gates(server_class, directory.name),
            'execution': {'jvm_started': False, 'server_started': False, 'network_attempted': False},
            'limits': ['No claim about prior denied-run payload receipt or successful transmission.',
                       'No claim of whole-process runtime network containment.']}


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('Supply local reviewed official Paper/Purpur fixture directories; no downloading occurs.')
    print(json.dumps({'audits': [audit(Path(value)) for value in sys.argv[1:]]}, indent=2))
