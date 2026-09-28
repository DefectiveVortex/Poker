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


def common_checks(log):
    lines = poker_lines(log)
    check('plugin enabled', any('Poker enabled.' in l for l in lines), 'no "Poker enabled." line')
    check('plugin not disabled', not any('Disabling Poker' in l for l in lines))
    bad = [l for l in log.splitlines()
           if ('com.vortex.poker' in l or '[Poker]' in l) and re.search(r'/(ERROR|SEVERE)\]|Exception', l)]
    check('no Poker errors or stack traces', not bad, ' | '.join(bad[:3]))
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

GOOD_TABLES = """next-id: 8
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
        b = backups_of('config.yml', before)
        check('broken config.yml backed up', bool(b), 'no new config.yml* file')
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
        check('old config backed up', bool(backups_of('config.yml', before)))
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
        check('broken messages.yml backed up', bool(backups_of('messages.yml', before)))
        ok, out = tables_listed([5, 7])
        check('tables #5 and #7 survive', ok, out)
    return verify


def case_tables_garbage():
    """tables.yml is not YAML -> plugin enables with no tables, the broken file is backed up (not overwritten)."""
    write('tables.yml', 'next-id: 3\ntables:\n  \'1\': {world: world, x: 0, y: -60\n  garbage ][ :::\n')

    def verify(log, before):
        b = backups_of('tables.yml', before)
        check('broken tables.yml backed up', bool(b), 'no new tables.yml* file')
        check('backup holds the broken text', any('garbage ][' in open(f, errors='replace').read() for f in b))
        t = parses('tables.yml')
        check('tables.yml parses (or is absent) after boot',
              t is None or isinstance(t, dict) or isinstance(t, FileNotFoundError), repr(t)[:120])
    return verify


def case_tables_one_bad():
    """One broken table among good ones -> the good ones survive, the bad one is reported, a backup is kept."""
    bad = GOOD_TABLES.replace("  '7':\n    world: world\n", "  '7':\n    world: no_such_world_xyz\n") + \
        "  '9':\n    world: world\n    x: notanumber\n    y: -60\n    z: 60\n    seats: banana\n"
    write('tables.yml', bad)

    def verify(log, before):
        ok, out = tables_listed([5])
        check('good table #5 survives', ok, out)
        check('broken table #9 is reported in the log', any(re.search(r'\b9\b', l) for l in poker_lines(log)
                                                            if re.search(r'WARN|skip|invalid|broken|bad', l, re.I)))
        t = parses('tables.yml')
        check('tables.yml still parses', isinstance(t, dict), repr(t)[:120])
        kept = t.get('tables', {}) if isinstance(t, dict) else {}
        check('unloadable table #7 (missing world) is not deleted from tables.yml', '7' in kept or 7 in kept,
              str(list(kept)))
    return verify


def case_tables_old():
    """tables.yml from round 1 (layout 1, no version) -> migrated, tables keep position and blinds."""
    old = GOOD_TABLES.replace('    layout: 2\n', '')
    write('tables.yml', old)

    def verify(log, before):
        ok, out = tables_listed([5, 7])
        check('old tables #5 and #7 load', ok, out)
        t = parses('tables.yml')
        seven = get(t, 'tables.7') if isinstance(t, dict) else None
        check('table #7 keeps blinds 25/50 and 4 seats',
              isinstance(seven, dict) and seven.get('small-blind') == 25 and seven.get('big-blind') == 50
              and seven.get('seats') == 4, str(seven))
    return verify


CASES = {
    'configMissing': case_config_missing,
    'configGarbage': case_config_garbage,
    'configOld': case_config_old,
    'messagesGarbage': case_messages_garbage,
    'tablesGarbage': case_tables_garbage,
    'tablesOneBad': case_tables_one_bad,
    'tablesOld': case_tables_old,
}


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
                common_checks(log)
                verify(log, before)
            except Exception as e:  # noqa: BLE001
                check(f'{name} ran', False, repr(e))
            finally:
                stop()
    finally:
        if os.environ.get('KEEP') != '1':
            shutil.rmtree(DATA, ignore_errors=True)
            shutil.move(saved, DATA)
    print(f'\n{passed} passed, {failed} failed')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
