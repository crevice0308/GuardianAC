#!/usr/bin/env node
'use strict';

// Run only against a disposable, prepared Purpur/Paper 1.20.4 fixture:
// NODE_PATH=/path/to/node_modules JAVA_HOME=/path/to/jdk node scripts/server-smoke.cjs /path/to/server
// Install minecraft-protocol@1.60.0 outside the checkout. Supply session proxy/CA JVM flags externally.
// This script never accepts the EULA, downloads a server, or edits server/world configuration.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { EventEmitter } = require('node:events');
const protocol = require('minecraft-protocol');

const serverDir = path.resolve(process.argv[2] || '');
assert(process.argv.length === 3, 'Usage: node scripts/server-smoke.cjs /path/to/prepared-server');
assert.equal(require('minecraft-protocol/package.json').version, '1.60.0', 'Use minecraft-protocol@1.60.0');

function properties(file) {
  return Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .map(line => line.trim()).filter(line => line && !/^[#!]/.test(line))
    .map(line => {
      const match = /^([^=:\s]+)\s*[=:]\s*(.*)$/.exec(line);
      assert(match, `Unsupported property line in ${path.basename(file)}`);
      return [match[1], match[2].trim()];
    }));
}

const config = properties(path.join(serverDir, 'server.properties'));
assert.equal(config['server-ip'], '127.0.0.1', 'Refusing a server that is not bound exclusively to 127.0.0.1');
assert.equal(config['online-mode'], 'false', 'The isolated protocol fixture must use offline mode');
assert.equal(config['allow-flight'], 'true', 'The fixture must disable vanilla flying kicks with allow-flight=true');
assert.equal(properties(path.join(serverDir, 'eula.txt')).eula, 'true', 'Accept the Minecraft EULA yourself before running');
assert.equal(config['enable-rcon'], 'false', 'The local fixture must disable RCON');
assert.equal(config['enable-query'], 'false', 'The local fixture must disable the public query endpoint');
const port = Number(config['server-port']);
assert(Number.isInteger(port) && port > 1023 && port <= 65535, 'Use an explicit unprivileged server-port');
assert(fs.statSync(path.join(serverDir, 'server.jar')).isFile(), 'A prepared server.jar is required');

const abort = new AbortController();
const deadline = setTimeout(() => abort.abort(new Error('Smoke test exceeded six minutes')), 360_000);
process.once('SIGINT', () => abort.abort(new Error('Interrupted')));
process.once('SIGTERM', () => abort.abort(new Error('Terminated')));
const log = fs.createWriteStream(path.join(serverDir, 'smoke.log'), { flags: 'w' });
const clients = new Set();
const checks = [];
let server;

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function bounded(promise, timeout, description, signal = abort.signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error(`Timed out: ${description}`)), timeout);
    const cancelled = () => finish(signal.reason || new Error('Aborted'));
    let settled = false;
    function finish(error, value) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal.removeEventListener('abort', cancelled);
      if (error) reject(error); else resolve(value);
    }
    signal.addEventListener('abort', cancelled, { once: true });
    if (signal.aborted) cancelled();
    Promise.resolve(promise).then(value => finish(null, value), error => finish(error));
  });
}

function delay(milliseconds) {
  return bounded(new Promise(resolve => setTimeout(resolve, milliseconds)), milliseconds + 1000, 'expiry/throttle grace');
}

function pass(message) {
  checks.push(message);
  process.stdout.write(`PASS ${message}\n`);
}

function plain(text) {
  return text.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '').replace(/§[0-9A-FK-OR]/gi, '');
}

class Server {
  constructor(phase) {
    this.phase = phase;
    this.output = '';
    this.events = new EventEmitter();
    this.exited = deferred();
    this.finished = false;
  }

  async start() {
    log.write(`\n===== ${this.phase} =====\n`);
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
    this.child = spawn(java, ['-Xms512M', '-Xmx1G', '-jar', 'server.jar', '--nogui'], {
      cwd: serverDir, stdio: ['pipe', 'pipe', 'pipe'], env: process.env
    });
    const append = chunk => {
      log.write(chunk);
      this.output += plain(chunk.toString('utf8'));
      this.events.emit('output');
    };
    this.child.stdout.on('data', append);
    this.child.stderr.on('data', append);
    this.child.stdin.on('error', error => { this.stdinError = error; });
    this.child.once('error', error => {
      this.failure = error;
      this.finished = true;
      this.exited.resolve({ error });
      this.events.emit('output');
    });
    this.child.once('exit', (code, signal) => {
      this.finished = true;
      this.exited.resolve({ code, signal });
      this.events.emit('output');
    });
    await this.waitForLog(/Done \([\d.]+s\)!/, 120_000);
    await this.waitForLog(/GuardianAC Ultimate enabled\./, 1000);
    await this.command('gac status', /GuardianAC Ultimate \| active \| storage=healthy/);
    pass(`${this.phase}: server ready and GuardianAC storage healthy`);
  }

  waitForLog(pattern, milliseconds = 15_000, offset = 0) {
    let listener;
    const result = new Promise((resolve, reject) => {
      listener = () => {
        const match = pattern.exec(this.output.slice(offset));
        if (match) resolve(match);
        else if (this.finished) reject(this.failure || new Error(`Server exited while waiting for ${pattern}; see smoke.log`));
      };
      this.events.on('output', listener);
      listener();
    });
    return bounded(result, milliseconds, `server log ${pattern}`).finally(() => this.events.off('output', listener));
  }

  async command(command, expected) {
    assert(!this.finished && this.child.stdin.writable, 'Server command input is closed');
    assert(!/[\r\n]/.test(command), 'Only one console command is allowed');
    const offset = this.output.length;
    this.child.stdin.write(command + '\n');
    if (!expected) return;
    return this.waitForLog(expected, 15_000, offset);
  }

  async stop() {
    if (!this.child || this.finished) return;
    this.child.stdin.end('stop\n');
    // Cleanup must still run after the test deadline or an interrupt.
    const cleanupSignal = new AbortController().signal;
    try {
      const result = await bounded(this.exited.promise, 30_000, 'clean server shutdown', cleanupSignal);
      assert.equal(result.code, 0, `Server exited abnormally: ${JSON.stringify(result)}`);
    } catch (failure) {
      this.child.kill('SIGTERM');
      try { await bounded(this.exited.promise, 10_000, 'server termination', cleanupSignal); }
      catch { this.child.kill('SIGKILL'); }
      throw failure;
    }
  }
}

function createBot(username) {
  const client = protocol.createClient({
    host: '127.0.0.1', port, username, version: '1.20.4', auth: 'offline',
    hideErrors: true, checkTimeoutInterval: 20_000
  });
  const bot = {
    client, username, ready: deferred(), ended: deferred(), disconnected: deferred(),
    reason: '', joined: false, error: null, lastPackets: []
  };
  clients.add(bot);
  let location = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 };
  client.on('state', state => {
    if (state === 'configuration') client.write('settings', {
      locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true,
      skinParts: 0, mainHand: 1, enableTextFiltering: false, enableServerListing: false
    });
  });
  client.on('position', packet => {
    for (const [key, flag] of [['x', 1], ['y', 2], ['z', 4], ['yaw', 8], ['pitch', 16]]) {
      location[key] = (packet.flags & flag ? location[key] : 0) + packet[key];
    }
    client.write('teleport_confirm', { teleportId: packet.teleportId });
    // The fixture stays at its server-selected standing spawn; it sends no synthetic cheat movement.
    client.write('position_look', { ...location, onGround: true });
    bot.joined = true;
    bot.ready.resolve(true);
  });
  client.on('packet', (packet, metadata) => {
    bot.lastPackets.push(`${metadata.state}.${metadata.name}`);
    if (bot.lastPackets.length > 16) bot.lastPackets.shift();
    if (metadata.name.includes('disconnect')) {
      bot.reason = plain(JSON.stringify(packet.reason));
      log.write(`[client ${username} ${metadata.state}.${metadata.name}] ${bot.reason}\n`);
      bot.disconnected.resolve(bot.reason);
    }
  });
  client.on('error', error => {
    bot.error = error;
    bot.ready.resolve(false);
  });
  client.once('end', reason => {
    if (!bot.reason) bot.reason = String(reason);
    bot.ready.resolve(false);
    bot.ended.resolve(bot);
    clients.delete(bot);
  });
  return bot;
}

async function join(username) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const bot = createBot(username);
    const ready = await bounded(bot.ready.promise, 15_000, `${username} reaches playable state`);
    if (ready) return bot;
    await bounded(bot.ended.promise, 3000, `${username} closes rejected connection`);
    if (!bot.error) await parsedDisconnect(bot);
    if (/throttl|Please wait before reconnecting/i.test(bot.reason)) {
      await delay(4100);
      continue;
    }
    throw new Error(`${username} could not join: ${bot.error?.message || bot.reason}`);
  }
  throw new Error(`${username} remained connection-throttled`);
}

async function assertBanDisconnect(bot, expectedPlayer = 'GuardianProbe1', expectedCheck = null, milliseconds = 10_000) {
  await bounded(bot.ended.promise, milliseconds, `${bot.username} receives persisted ban disconnect`);
  await parsedDisconnect(bot);
  assert.match(bot.reason, /Access suspended/, `Unexpected disconnect: ${bot.reason}`);
  assert(bot.reason.includes(expectedPlayer), 'Ban screen must show the punished account name');
  if (expectedCheck) assert(bot.reason.includes(expectedCheck), `Ban screen must identify ${expectedCheck}`);
}

async function parsedDisconnect(bot) {
  // The TCP 'end' event can precede minecraft-protocol's asynchronous zlib decompression.
  // Assert the decoded disconnect packet itself, rather than the transport's socketClosed reason.
  try {
    await bounded(bot.disconnected.promise, 3000, `${bot.username} decodes its disconnect packet`);
  } catch (failure) {
    throw new Error(`${failure.message}; client error=${bot.error?.message || 'none'}; recent packets=${bot.lastPackets.join(', ')}`);
  }
}

async function denied(username, expectedPlayer = 'GuardianProbe1') {
  for (let attempt = 0; attempt < 3; attempt++) {
    const bot = createBot(username);
    await bounded(bot.ended.promise, 15_000, `${username} is denied login`);
    await parsedDisconnect(bot);
    if (/throttl|Please wait before reconnecting/i.test(bot.reason)) {
      await delay(4100);
      continue;
    }
    assert.equal(bot.joined, false, `${username} unexpectedly entered the world`);
    await assertBanDisconnect(bot, expectedPlayer);
    return;
  }
  throw new Error(`${username} remained connection-throttled instead of reaching GuardianAC`);
}

async function closeBot(bot) {
  if (!clients.has(bot)) return;
  bot.client.end('GuardianAC smoke test disconnect');
  await bounded(bot.ended.promise, 3000, `${bot.username} disconnects normally`);
}

async function main() {
  server = new Server('initial startup');
  await server.start();
  let target = await join('GuardianProbe1');
  await server.command('gac ipban GuardianProbe1 30s Smoke-test', /Banned GuardianProbe1 until/);
  await assertBanDisconnect(target);
  const saved = fs.readFileSync(path.join(serverDir, 'plugins', 'GuardianAC', 'bans.json'), 'utf8');
  JSON.parse(saved);
  assert(saved.includes('GuardianProbe1') && saved.includes('127.0.0.1'), 'Dedicated ban data must persist the name and IP');
  pass('IP BAN is persisted and its disconnect screen names GuardianProbe1');

  await denied('GuardianProbe1');
  await denied('GuardianProbe2');
  pass('UUID and different account on the banned IP are both denied login');

  await server.command('gac unban GuardianProbe1', /Removed \d+ ban\(s\), including linked IP restrictions/);
  target = await join('GuardianProbe1');
  const other = await join('GuardianProbe2');
  await closeBot(other);
  pass('UNBAN permits both the original UUID and another account sharing its IP');

  await server.command('gac ban GuardianProbe1 1s Expiry-test', /Banned GuardianProbe1 until/);
  await assertBanDisconnect(target);
  await delay(1250);
  target = await join('GuardianProbe1');
  pass('One-second temporary BAN expires and permits rejoining');

  await server.command('gac ban GuardianProbe1 permanent Smoke-permanent', /Banned GuardianProbe1 until Permanent/);
  await assertBanDisconnect(target);
  await server.stop();
  server = new Server('persistence restart');
  await server.start();
  await denied('GuardianProbe1');
  const unbannedAccount = await join('GuardianProbe2');
  await closeBot(unbannedAccount);
  pass('Permanent UUID BAN survives restart without restricting another UUID on its IP');

  await server.command('gac unban GuardianProbe1', /Removed \d+ ban\(s\), including linked IP restrictions/);
  target = await join('GuardianProbe1');
  await closeBot(target);
  pass('UNBAN removes the restarted permanent BAN and restores login');

  // The server allows flying packets, but this survival player has no flight permission/ability.
  // Holding an unsupported height exercises the real scheduler, evidence buffers, VL and auto-persistence.
  const flyOffset = server.output.length;
  const flyer = await join('GuardianFly');
  // Vanilla emits no success message when the requested game mode is already active.
  await server.command('gamemode survival GuardianFly');
  await server.command('data get entity GuardianFly abilities.mayfly',
    /GuardianFly has the following entity data: 0b/);
  await server.command('tp GuardianFly 0 300 0', /Teleported GuardianFly to/);
  await assertBanDisconnect(flyer, 'GuardianFly', 'FLY', 30_000);
  await server.waitForLog(/GuardianFly has been banned\./, 1000, flyOffset);
  const autoData = JSON.parse(fs.readFileSync(path.join(serverDir, 'plugins', 'GuardianAC', 'bans.json'), 'utf8'));
  const automatic = autoData.bans.find(ban => ban.playerName === 'GuardianFly');
  assert(automatic, 'The automatic FLY penalty must be persisted');
  assert.equal(automatic.source, 'automatic:FLY');
  assert.equal(automatic.address, '127.0.0.1', 'Default automatic policy must persist a linked IP restriction');
  assert(automatic.expiresAt > automatic.createdAt && automatic.expiresAt > 0,
    'The automatic penalty must have the configured temporary expiry');
  await denied('GuardianFly', 'GuardianFly');
  await denied('GuardianProbe2', 'GuardianFly');
  pass('Real unsupported hover triggers automatic FLY UUID/IP BAN and a named global broadcast');

  await server.command('gac unban GuardianFly', /Removed \d+ ban\(s\), including linked IP restrictions/);
  const releasedFlyer = await join('GuardianFly');
  await closeBot(releasedFlyer);
  pass('UNBAN clears the automatic FLY penalty and restores login');
}

(async () => {
  try {
    await main();
  } catch (failure) {
    process.exitCode = 1;
    process.stderr.write(`FAIL ${failure.stack || failure}\nSee ${path.join(serverDir, 'smoke.log')}\n`);
  } finally {
    clearTimeout(deadline);
    for (const bot of clients) bot.client.end('GuardianAC smoke test cleanup');
    if (server && !server.finished) {
      // Cleanup only the dedicated fixture account; do not clear unrelated server penalties.
      if (!abort.signal.aborted) {
        for (const name of ['GuardianProbe1', 'GuardianFly']) {
          try { await server.command(`gac unban ${name}`, /Removed \d+ ban\(s\), including linked IP restrictions/); }
          catch (failure) { process.stderr.write(`Cleanup UNBAN ${name}: ${failure.message}\n`); }
        }
      }
      try { await server.stop(); }
      catch (failure) {
        process.exitCode = 1;
        process.stderr.write(`Cleanup shutdown: ${failure.message}\n`);
      }
    }
    await new Promise(resolve => log.end(resolve));
    process.stdout.write(JSON.stringify({ passed: checks.length, success: !process.exitCode, log: path.join(serverDir, 'smoke.log') }) + '\n');
  }
})();
