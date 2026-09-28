#!/usr/bin/env python3
"""Server-level file checks for Poker (D1). Each case breaks the plugin's data files, boots the test server, and checks
that the plugin enabled, repaired the files, kept a backup and kept what it could (tables, settings).

  python3 filecheck.py [case ...]        (default: all cases; exit 0 = all passed)
  env: SERVER_DIR (default paper-26.3), SERVER_SH, KEEP=1 (don't restore the original data folder afterwards)

Boots the server itself (server.sh start/stop), so the test-server slot must be free. The original plugins/Poker folder
is copied aside first and put back at the end, even on failure.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import time

import yaml

OPS = '/home/vortex/Poker-ops/test-server'
SERVER_DIR = os.environ.get('SERVER_DIR', OPS + '/paper-26.3')
SERVER_SH = os.environ.get('SERVER_SH', OPS + '/server.sh')
DATA = os.path.join(SERVER_DIR, 'plugins', 'Poker')
HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, 'fixtures')

passed = failed = 0


def check(label, ok, detail=''):
    global passed, failed
    if ok:
        passed += 1
        print(f'  ok   {label}')
    else:
        failed += 1
        print(f'  FAIL {label}' + (f'  -- {detail}' if detail else ''))


def sh(*args, check_rc=True):
    r = subprocess.run(args, capture_output=True, text=True)
    if check_rc and r.returncode != 0:
        raise RuntimeError(f'{args}: {r.stdout}{r.stderr}')
    return r.stdout


def rcon(cmd):
    return sh('python3', OPS + '/rcon.py', '127.0.0.1', '25581', 'poker-test', *cmd.split(), check_rc=False)


def strip_colours(s):
    return re.sub(r'§.', '', s)


def boot():
    """Start the server and wait for Done; returns this boot's log text."""
    log = os.path.join(SERVER_DIR, 'logs', 'latest.log')
    if os.path.exists(log):
        os.remove(log)  # previous logs are gzipped already; this one would be rotated anyway
    sh(SERVER_SH, 'start', os.path.basename(SERVER_DIR))
    deadline = time.time() + 240
    while time.time() < deadline:
        time.sleep(3)
        if os.path.exists(log):
            text = open(log, errors='replace').read()
            if re.search(r'Done \([\d.]+s\)!', text):
                time.sleep(4)  # let first-tick tasks (economy reconnect, table spawn) run
                return open(log, errors='replace').read()
    raise RuntimeError('server did not finish booting in 240 s')


def stop():
    sh(SERVER_SH, 'stop', check_rc=False)


def poker_lines(log):
    return [l for l in log.splitlines() if '[Poker]' in l or 'com.vortex.poker' in l]


def common_checks(log, allowed=()):
    lines = poker_lines(log)
    check('plugin enabled', any('Poker enabled.' in l for l in lines), 'no "Poker enabled." line')
    check('plugin not disabled', not any('Disabling Poker' in l for l in lines))
    bad = [l for l in log.splitlines()
           if ('com.vortex.poker' in l or '[Poker]' in l) and re.search(r'/(ERROR|SEVERE)\]|Exception', l)
           and not any(a in l for a in allowed)]
    check('no Poker errors or stack traces' + (' (besides the expected ones)' if allowed else ''), not bad, ' | '.join(bad[:3]))
    check('no tables.yml.tmp left over', not os.path.exists(os.path.join(DATA, 'tables.yml.tmp')))
    out = strip_colours(rcon('poker version'))
    check('/poker version answers', 'Poker' in out or '1.' in out, out.strip()[:120])


def parses(name):
    try:
        with open(os.path.join(DATA, name)) as f:
            return yaml.safe_load(f)
    except Exception as e:  # noqa: BLE001
        return e


def backups_of(name, before):
    """Files next to (or in a backups/ folder under) DATA that mention `name` and weren't there before the boot."""
    found = set(glob.glob(os.path.join(DATA, name + '*'))) | set(glob.glob(os.path.join(DATA, '**', name + '*'),
                                                                        recursive=True))
    found.discard(os.path.join(DATA, name))
    return sorted(f for f in found if f not in before)


def snapshot():
    return set(glob.glob(os.path.join(DATA, '**', '*'), recursive=True))


def tables_listed(ids):
    out = strip_colours(rcon('poker tables'))
    return all(re.search(rf'#{i}\b', out) for i in ids), out.strip().replace('\n', ' | ')[:200]


def get(d, path):
    for k in path.split('.'):
        if not isinstance(d, dict) or k not in d:
            return None
        d = d[k]
    return d


# ---------------------------------------------------------------- cases
# Each case: prepare() edits DATA (server stopped) and returns anything verify() needs; verify(log, before, state).

GOOD_TABLES = """format-version: 1
next-id: 8
tables:
  '5':
    world: world
    x: 20
    y: -60
    z: 20
    facing: NORTH
    layout: 2
    seats: 6
    small-blind: 5
    big-blind: 10
    min-buy-in-bb: 10
    max-buy-in-bb: 200
  '7':
    world: world
    x: 40
    y: -60
    z: 20
    facing: EAST
    layout: 2
    seats: 4
    small-blind: 25
    big-blind: 50
    min-buy-in-bb: 20
    max-buy-in-bb: 100
"""


def write(name, text):
    with open(os.path.join(DATA, name), 'w') as f:
        f.write(text)


def case_config_missing():
    """config.yml deleted -> recreated with defaults; tables untouched."""
    os.remove(os.path.join(DATA, 'config.yml'))
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        cfg = parses('config.yml')
        check('config.yml recreated and parses', isinstance(cfg, dict), repr(cfg)[:120])
        check('config.yml has defaults (game.ready-timeout-seconds)', get(cfg, 'game.ready-timeout-seconds') is not None)
        check('config-version: 1', get(cfg, 'config-version') == 1, str(get(cfg, 'config-version')))
        check('log: Created config.yml from bundled defaults', 'Created config.yml from bundled defaults' in log)
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_config_garbage():
    """config.yml is not YAML -> backed up, replaced with defaults, plugin still enables."""
    write('config.yml', 'game: [unclosed\n  :: this is : not yaml {{{\n\t\ttabs\n')
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        cfg = parses('config.yml')
        check('config.yml repaired and parses', isinstance(cfg, dict), repr(cfg)[:120])
        b = [f for f in backups_of('config.yml', before) if '.broken-' in f]
        check('broken config.yml moved to config.yml.broken-<ts>', bool(b), str(backups_of('config.yml', before)))
        check('log: could not be read ... .broken-', any('config.yml could not be read' in l and '.broken-' in l
                                                         for l in poker_lines(log)))
        check('backup holds the broken text', any('this is : not yaml' in open(f, errors='replace').read() for f in b))
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_config_old():
    """A pre-1.0 config (round-1 main e8630b0) with edited values -> migrated, edited values kept, new keys added."""
    old = sh('git', '-C', '/home/vortex/Poker', 'show', 'e8630b0:src/main/resources/config.yml')
    old = old.replace('turn-timeout-seconds: 30', 'turn-timeout-seconds: 47')
    old = old.replace('small-blind: 10', 'small-blind: 15')
    old = old.replace('menu: gui', 'menu: chat')
    assert 'turn-timeout-seconds: 47' in old and 'small-blind: 15' in old and 'menu: chat' in old
    old += '\n# a user comment that must survive\nshow-hand-strength: true\nfoo-unknown: bar\n'
    write('config.yml', old)
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        cfg = parses('config.yml')
        check('config.yml parses', isinstance(cfg, dict), repr(cfg)[:120])
        check('kept game.turn-timeout-seconds 47', get(cfg, 'game.turn-timeout-seconds') == 47,
              str(get(cfg, 'game.turn-timeout-seconds')))
        check('kept table.small-blind 15', get(cfg, 'table.small-blind') == 15, str(get(cfg, 'table.small-blind')))
        check('kept interface.menu chat', get(cfg, 'interface.menu') == 'chat', str(get(cfg, 'interface.menu')))
        check('new key added (game.ready-timeout-seconds)', get(cfg, 'game.ready-timeout-seconds') is not None)
        check('new key added (interface.show-hand-strength)', get(cfg, 'interface.show-hand-strength') is not None)
        check('moved top-level show-hand-strength -> interface.show-hand-strength true',
              get(cfg, 'interface.show-hand-strength') is True and 'show-hand-strength' not in cfg,
              str(get(cfg, 'interface.show-hand-strength')))
        check('unknown key foo-unknown kept', get(cfg, 'foo-unknown') == 'bar')
        check('user comment kept', 'a user comment that must survive' in open(os.path.join(DATA, 'config.yml')).read())
        check('config-version: 1', get(cfg, 'config-version') == 1, str(get(cfg, 'config-version')))
        check('old config copied to config.yml.pre-update.bak',
              os.path.exists(os.path.join(DATA, 'config.yml.pre-update.bak'))
              and 'turn-timeout-seconds: 47' in open(os.path.join(DATA, 'config.yml.pre-update.bak')).read())
        check('log: Updated config.yml: ... Backup: config.yml.pre-update.bak',
              any('Updated config.yml:' in l and 'config.yml.pre-update.bak' in l for l in poker_lines(log)))
        check('log: unknown key(s) ... foo-unknown', any('unknown key(s)' in l and 'foo-unknown' in l
                                                         for l in poker_lines(log)))
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_messages_garbage():
    """messages.yml is not YAML -> backed up and repaired; config edits unaffected."""
    write('messages.yml', 'prefix: "&8[&aPoker&8] \n  broken: [ { \n')
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        msgs = parses('messages.yml')
        check('messages.yml repaired and parses', isinstance(msgs, dict), repr(msgs)[:120])
        check('broken messages.yml moved to messages.yml.broken-<ts>',
              any('.broken-' in f for f in backups_of('messages.yml', before)))
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_config_bad_values():
    """Wrong types/ranges -> file left alone (no rewrite, no backup), defaults used in memory, one warning each."""
    path = os.path.join(DATA, 'config.yml')
    text = sh('unzip', '-p', os.path.join(DATA, '..', 'Poker.jar'), 'config.yml')  # a current config, so no migration
    text = re.sub(r'(?m)^(  big-blind:).*$', r'\1 lots', text, count=1)
    text = re.sub(r'(?m)^(  turn-timeout-seconds:).*$', r'\1 -5', text, count=1)
    assert 'big-blind: lots' in text and 'turn-timeout-seconds: -5' in text
    write('config.yml', text)
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        check('config.yml not rewritten', open(path).read() == text)
        check('no backup made', not backups_of('config.yml', before), str(backups_of('config.yml', before)))
        check('log: table.big-blind ... using', any('config.yml: table.big-blind' in l and 'using' in l
                                                     for l in poker_lines(log)))
        check('log: game.turn-timeout-seconds ... using', any('config.yml: game.turn-timeout-seconds' in l
                                                              for l in poker_lines(log)))
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_stats_garbage():
    """stats.yml half-broken -> moved to .broken-<ts>, readable players recovered."""
    write('stats.yml', "00000000-0000-0000-0000-00000000000a:\n  name: Alice\n  hands-played: 12\n  hands-won: 3\n"
                       "00000000-0000-0000-0000-00000000000b: [broken {\n  name: Bob\n")
    write('tables.yml', GOOD_TABLES)

    def verify(log, before):
        check('broken stats.yml moved to stats.yml.broken-<ts>',
              any('.broken-' in f for f in backups_of('stats.yml', before)), str(backups_of('stats.yml', before)))
        check('log: stats.yml could not be read', any('stats.yml could not be read' in l for l in poker_lines(log)))
        check('log: recovered 1 player(s)', any('stats.yml could not be read' in l and 'recovered 1 player' in l
                                                 for l in poker_lines(log)))
        s = parses('stats.yml')
        check('stats.yml parses after boot (or is absent until the next save)',
              isinstance(s, (dict, FileNotFoundError)) or s is None, repr(s)[:120])
    return verify


def case_tables_garbage():
    """tables.yml is not YAML at all -> renamed to .broken-<ts>, rewritten empty with next-id past any ID seen."""
    write('tables.yml', "next-id: 3\ntables:\n  '12': {world: world, x: 0, y: -60\n  garbage ][ :::\n")

    def verify(log, before):
        b = [f for f in backups_of('tables.yml', before) if '.broken-' in f]
        check('broken tables.yml moved to tables.yml.broken-<ts>', bool(b), str(backups_of('tables.yml', before)))
        check('backup holds the broken text', any('garbage ][' in open(f, errors='replace').read() for f in b))
        check('log: tables.yml was not valid YAML', any('tables.yml was not valid YAML' in l for l in poker_lines(log)))
        t = parses('tables.yml')
        check('tables.yml rewritten: format-version 1, no tables', isinstance(t, dict) and t.get('format-version') == 1
              and not t.get('tables'), repr(t)[:160])
        check('next-id past the ID seen in the garbage (>12)', isinstance(t, dict) and (t.get('next-id') or 0) > 12,
              repr(t)[:160])
        out = strip_colours(rcon('poker tables'))
        check('no tables loaded', not re.search(r'#\d+', out), out.strip()[:160])
    verify.allowed = ('tables.yml was not valid YAML',)
    return verify


def case_tables_one_bad():
    """Valid YAML with bad entries -> good ones load; bad ones stay byte-for-byte in tables.yml, with a warning each."""
    bad_entry = "  '9':\n    world: world\n    x: notanumber\n    y: -60\n    z: 60\n    seats: 6\n    layout: 2\n"
    text = GOOD_TABLES.replace("  '7':\n    world: world\n", "  '7':\n    world: no_such_world_xyz\n") + bad_entry
    write('tables.yml', text)

    def verify(log, before):
        ok, out = tables_listed([5])
        check('good table #5 loads', ok, out)
        check('#7 and #9 not loaded', not re.search(r'#(7|9)\b', out), out)
        lines = poker_lines(log)
        check("log: table '9' not loaded: x is not a whole number",
              any("'9'" in l and 'x is not a whole number: notanumber' in l for l in lines))
        check("log: table #7 waits for world 'no_such_world_xyz'",
              any('#7' in l and 'no_such_world_xyz' in l and "isn't loaded" in l for l in lines))
        check('log: summary mentions skipped / waiting', any(re.search(r'Loaded \d+ poker table\(s\).*(skipped|waiting)', l)
                                                             for l in lines))
        rcon('poker settable 5 small-blind:10')  # forces a save if settable accepts console + id; harmless if not
        cur = open(os.path.join(DATA, 'tables.yml')).read()
        check('bad entry #9 still byte-for-byte in tables.yml', bad_entry in cur, cur[-300:])
        check('#7 (missing world) still in tables.yml', 'no_such_world_xyz' in cur)
    return verify


def case_tables_broken_entry():
    """One syntactically broken entry makes the file unparseable -> good entries salvaged, broken one only in .broken."""
    text = GOOD_TABLES + "  '9':\n    world: world\n    x: [1, 2\n    z: {{ ::\n"
    write('tables.yml', text)

    def verify(log, before):
        ok, out = tables_listed([5, 7])
        check('good tables #5 and #7 salvaged', ok, out)
        b = [f for f in backups_of('tables.yml', before) if '.broken-' in f]
        check('original kept as tables.yml.broken-<ts>', bool(b) and open(b[0]).read() == text,
              str(backups_of('tables.yml', before)))
        lines = poker_lines(log)
        check("log: table '9' not loaded, not valid YAML (lines ...), only in .broken",
              any("'9'" in l and 'not valid YAML (lines' in l and '.broken-' in l for l in lines))
        t = parses('tables.yml')
        kept = (t or {}).get('tables', {}) if isinstance(t, dict) else {}
        check('tables.yml rewritten with #5 and #7', isinstance(t, dict) and {'5', '7'} <= {str(k) for k in kept},
              repr(t)[:160])
    verify.allowed = ('tables.yml was not valid YAML',)
    return verify


def case_tables_old():
    """tables.yml from before format 1 (no format-version, no layout) -> backed up, migrated, rebuilt as 3x3."""
    old = GOOD_TABLES.replace('format-version: 1\n', '').replace('    layout: 2\n', '')
    write('tables.yml', old)

    def verify(log, before):
        ok, out = tables_listed([5, 7])
        check('old tables #5 and #7 load', ok, out)
        bk = os.path.join(DATA, 'tables.yml.format-0-backup')
        check('tables.yml.format-0-backup holds the old file', os.path.exists(bk) and open(bk).read() == old)
        lines = poker_lines(log)
        check('log: upgraded format 0 to 1', any('tables.yml: upgraded format 0 to 1' in l for l in lines))
        check('log: Rebuilt poker table #5 and #7', all(any(f'Rebuilt poker table #{i}' in l for l in lines)
                                                         for i in (5, 7)))
        t = parses('tables.yml')
        seven = get(t, 'tables.7') if isinstance(t, dict) else None
        check('format-version 1 and layout 2 on every table', isinstance(t, dict) and t.get('format-version') == 1
              and all((v or {}).get('layout') == 2 for v in (t.get('tables') or {}).values()), repr(t)[:200])
        check('table #7 keeps position, facing, blinds 25/50 and 4 seats',
              isinstance(seven, dict) and (seven.get('x'), seven.get('z'), seven.get('facing')) == (40, 20, 'EAST')
              and seven.get('small-blind') == 25 and seven.get('big-blind') == 50 and seven.get('seats') == 4,
              str(seven))
    return verify


# ---------------------------------------------------------------- updater (D2's modrinth-mock.py)
MOCK_PORT = 25590
MOCK = f'http://172.17.0.1:{MOCK_PORT}'
mock_proc = None


def start_mock(*args):
    global mock_proc
    stop_mock()
    served = os.path.join(DATA, '..', 'Poker.jar')
    mock_proc = subprocess.Popen(['python3', os.path.join(HERE, 'modrinth-mock.py'), '--jar', served, '--port',
                                  str(MOCK_PORT), *args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(20):
        time.sleep(0.25)
        if subprocess.run(['curl', '-sf', MOCK + '/mock'], capture_output=True).returncode == 0:
            return
    raise RuntimeError('modrinth-mock did not come up')


def stop_mock():
    global mock_proc
    if mock_proc:
        mock_proc.terminate()
        mock_proc.wait(5)
        mock_proc = None


def mock(query=''):
    import json
    import urllib.request
    return json.load(urllib.request.urlopen(MOCK + '/mock' + query, timeout=5))


def mock_log():
    import json
    import urllib.request
    return json.load(urllib.request.urlopen(MOCK + '/mock/log', timeout=5))


def set_api_url(url):
    path = os.path.join(DATA, 'config.yml')
    text = open(path).read()
    text = re.sub(r'(?m)^  api-url:.*\n', '', text)
    if re.search(r'(?m)^updates:\s*$', text):
        text = re.sub(r'(?m)^updates:\s*$', f'updates:\n  api-url: {url}', text, count=1)
    else:
        text += f'\nupdates:\n  api-url: {url}\n'
    write('config.yml', text)


UPDATE_DIR = os.path.join(DATA, '..', 'update')


def update_files():
    return sorted(os.listdir(UPDATE_DIR)) if os.path.isdir(UPDATE_DIR) else []


def sha512(path):
    import hashlib
    return hashlib.sha512(open(path, 'rb').read()).hexdigest()


def log_since(n):
    return open(os.path.join(SERVER_DIR, 'logs', 'latest.log'), errors='replace').read().splitlines()[n:]


def log_len():
    return len(open(os.path.join(SERVER_DIR, 'logs', 'latest.log'), errors='replace').read().splitlines())


def poker_warn_or_worse(lines):
    return [l for l in lines if ('[Poker]' in l or 'com.vortex.poker' in l)
            and re.search(r'/(WARN|ERROR|SEVERE)\]|Exception', l)]


def case_update_no_network():
    """updates.api-url points at a port nobody listens on -> nothing at INFO or above, no stack trace."""
    shutil.rmtree(UPDATE_DIR, ignore_errors=True)
    set_api_url('http://172.17.0.1:25599/v2')

    def verify(log, before):
        time.sleep(8)  # first check runs 5 s after enable
        n = log_len()
        out = strip_colours(rcon('poker update'))
        time.sleep(4)
        lines = log_since(0)
        check('no Poker warnings/errors/stack traces with no network', not poker_warn_or_worse(lines),
              ' | '.join(poker_warn_or_worse(lines)[:3]))
        check('no Poker INFO about updates either', not any('[Poker]' in l and 'Modrinth' in l for l in lines))
        check('poker update replies (check failed)', bool(out.strip()) and 'Missing message' not in out, out.strip()[:160])
        check('nothing staged in plugins/update', not update_files(), str(update_files()))
    return verify


def case_update_flow():
    """Against the mock: same version -> nothing; bad hash -> rejected, nothing left; good -> downloaded + verified;
    second update -> no re-download; 404 -> quiet up to date."""
    shutil.rmtree(UPDATE_DIR, ignore_errors=True)
    start_mock('--version', '1.0.0')
    set_api_url(MOCK + '/v2')

    def verify(log, before):
        served = os.path.join(DATA, '..', 'Poker.jar')
        time.sleep(8)
        lines = log_since(0)
        check('startup check hit the mock', any('/project/' in r['path'] for r in mock_log()), str(mock_log())[:200])
        check('User-Agent DefectiveVortex/Poker/1.0.0', all(r['user_agent'].startswith('DefectiveVortex/Poker/')
                                                            for r in mock_log()),
              str({r['user_agent'] for r in mock_log()}))
        check('same version: no download, nothing logged', not update_files()
              and not any('[Poker]' in l and 'Modrinth' in l for l in lines), str(update_files()))
        rcon('poker update')  # the result is sent asynchronously, after RCON has returned; read it back from version
        time.sleep(4)
        out = strip_colours(rcon('poker version'))
        check('same version: poker version says up to date', 'latest' in out.lower() or 'up to date' in out.lower(),
              out.strip()[:160])

        mock('?version=1.0.1&bad=1&status=200')
        n = log_len()
        out = strip_colours(rcon('poker update'))
        time.sleep(6)
        new = log_since(n)
        check('bad hash: rejected in the log', any('Rejected the Poker 1.0.1 download: sha512 mismatch' in l for l in new),
              ' | '.join(l for l in new if '[Poker]' in l)[:300])
        check('bad hash: nothing (no jar, no .part) in plugins/update', not update_files(), str(update_files()))

        mock('?bad=0')
        n = log_len()
        out = strip_colours(rcon('poker update'))
        time.sleep(6)
        new = log_since(n)
        check('good hash: "Downloaded Poker 1.0.1 (sha512 verified)"',
              any('Downloaded Poker 1.0.1 (sha512 verified)' in l for l in new),
              ' | '.join(l for l in new if '[Poker]' in l)[:300])
        staged = os.path.join(UPDATE_DIR, 'Poker.jar')
        check('staged as plugins/update/Poker.jar, only file there', update_files() == ['Poker.jar'], str(update_files()))
        check('staged jar sha512 matches the served jar', os.path.exists(staged) and sha512(staged) == sha512(served))
        files_hits = sum('/files/' in r['path'] for r in mock_log())

        out = strip_colours(rcon('poker update'))
        time.sleep(4)
        check('second update: no re-download', sum('/files/' in r['path'] for r in mock_log()) == files_hits,
              f'{files_hits} -> {sum("/files/" in r["path"] for r in mock_log())}')
        check('poker version shows 1.0.1 available', '1.0.1' in strip_colours(rcon('poker version')))

        mock('?status=404')
        n = log_len()
        out = strip_colours(rcon('poker update'))
        time.sleep(4)
        check('404 (unpublished project): no warnings, no stack trace', not poker_warn_or_worse(log_since(n)))
        all_lines = log_since(0)
        check('no Poker errors or stack traces during the whole flow', not [
            l for l in all_lines if ('com.vortex.poker' in l or '[Poker]' in l) and re.search(r'/(ERROR|SEVERE)\]|Exception', l)])
    return verify


def case_update_applied():
    """After updateFlow staged update/Poker.jar: a restart moves it over plugins/Poker.jar and leaves update/ empty.
    The mock now advertises the running version, so nothing is downloaded again."""
    staged = os.path.join(UPDATE_DIR, 'Poker.jar')
    if not os.path.exists(staged):
        raise RuntimeError('run updateFlow first (nothing staged)')
    live = os.path.join(DATA, '..', 'Poker.jar')
    staged_ino = os.stat(staged).st_ino
    start_mock('--version', '1.0.0')
    set_api_url(MOCK + '/v2')

    def verify(log, before):
        time.sleep(8)
        check('plugins/update is empty after the restart', not update_files(), str(update_files()))
        check('plugins/Poker.jar is the staged file (moved into place)', os.stat(live).st_ino == staged_ino
              or os.stat(live).st_mode & 0o777 == 0o600, oct(os.stat(live).st_mode))
        check('no download after applying', not any('/files/' in r['path'] for r in mock_log()))
    return verify


CASES = {
    'configMissing': case_config_missing,
    'configGarbage': case_config_garbage,
    'configOld': case_config_old,
    'configBadValues': case_config_bad_values,
    'messagesGarbage': case_messages_garbage,
    'statsGarbage': case_stats_garbage,
    'tablesGarbage': case_tables_garbage,
    'tablesOneBad': case_tables_one_bad,
    'tablesBrokenEntry': case_tables_broken_entry,
    'tablesOld': case_tables_old,
    'updateNoNetwork': case_update_no_network,
    'updateFlow': case_update_flow,
    'updateApplied': case_update_applied,
}
# updateApplied relies on updateFlow's staged jar surviving the data-folder restore, which only touches plugins/Poker.


def main():
    names = sys.argv[1:] or list(CASES)
    unknown = [n for n in names if n not in CASES]
    if unknown:
        sys.exit(f'unknown case(s) {unknown}; have {list(CASES)}')
    if 'true' in sh('docker', 'inspect', '-f', '{{.State.Running}}', 'poker-test', check_rc=False):
        sys.exit('poker-test is running; stop it first (filecheck boots the server itself)')
    saved = DATA + '.filecheck-orig'
    if os.path.exists(saved):
        sys.exit(f'{saved} exists from an interrupted run; restore or remove it first')
    shutil.copytree(DATA, saved)
    try:
        for name in names:
            print(f'\n== {name} ({os.path.basename(SERVER_DIR)})')
            shutil.rmtree(DATA)
            shutil.copytree(saved, DATA)
            for f in glob.glob(os.path.join(DATA, '*.bak')) + glob.glob(os.path.join(DATA, 'backups')):
                shutil.rmtree(f) if os.path.isdir(f) else os.remove(f)
            verify = CASES[name]()
            before = snapshot()
            try:
                log = boot()
                common_checks(log, getattr(verify, 'allowed', ()))
                verify(log, before)
            except Exception as e:  # noqa: BLE001
                check(f'{name} ran', False, repr(e))
            finally:
                stop()
                stop_mock()
    finally:
        if os.environ.get('KEEP') != '1':
            shutil.rmtree(DATA, ignore_errors=True)
            shutil.move(saved, DATA)
    print(f'\n{passed} passed, {failed} failed')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
