"""Paired host benchmark; no user database, network service or Android device."""
import argparse
from collections import Counter
from datetime import date, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import statistics
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PKG = 'app/src/main/java/com/douai/module/analytics/'
BASE = '897c231e7e7da8663c50870709c43324f875b2bb'
BASELINE_DIR = None
COMMON = ['LocalAnalytics', 'AnalyticsSnapshot', 'PortraitSnapshot', 'PortraitMetrics', 'AnalyticsBackup']
END = date(2026, 11, 11)
TYPES = ('image', 'short_video', 'live_photo', 'long_video')
ACTIONS = [('save_media', 'entry'), ('copy_text', 'success')]


def source(name, old):
    path = PKG + name + '.java'
    if old:
        if BASELINE_DIR:
            return (BASELINE_DIR / path).read_bytes()
        return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT)
    return (ROOT / path).read_bytes()


def fixture(path):
    # Bootstrap real v2 schema. Stage the v3-only event and coverage rows identically
    # in both copies; the old code ignores them, while the current code migrates v2.
    db = sqlite3.connect(path)
    def ddl(old):
        return re.findall(r'opened\.execSQL\("(CREATE (?:TABLE|INDEX) IF NOT EXISTS [^"]+)"\);',
                          source('LocalAnalytics', old).decode())
    for statement in ddl(True):
        db.execute(statement)
    for statement in ddl(False):
        if re.match('CREATE TABLE IF NOT EXISTS (flow_events|source_coverage)\\(', statement):
            db.execute(statement)
    db.execute('PRAGMA user_version = 2')
    first = END - timedelta(days=364)
    first_ms = (first-date(1970,1,1)).days*86400000
    db.executemany('INSERT INTO meta VALUES (?,?)', [('collection_start',first_ms),('first_seen',first_ms)])
    db.execute('INSERT INTO collection_boundaries(ts,enabled) VALUES (?,1)', (first_ms,))
    db.executemany("INSERT INTO source_coverage VALUES (?,'available',?,NULL)",
                   [('visits',first_ms),('events',first_ms)])
    nvis = acts = flows = 0
    for d in range(365):
        today = first + timedelta(days=d)
        day = today.isoformat()
        # ~667 visits per day throughout the year; retain exactly 20,000 raw visits in the final 30.
        n = 666 + (d % 3 != 0)
        start_ms = first_ms + d*86400000
        works, ranks, hours = set(), Counter(), Counter()
        visits, tags, actions, events = [], [], [], []
        for j in range(n):
            seq = d * 667 + j
            work = 'w' + str((seq % 12001) if j%4 else (900000+seq))
            typ, topic, author = TYPES[j%4], 'topic'+str(seq%24), 'author'+str(seq%96)
            ts = start_ms + (j * 86400000 // n)
            works.add(work)
            hour = (ts-start_ms)//3600000
            hours[hour] += 1
            ranks['type',typ] += 1
            ranks['author',author] += 1
            topics = [topic] + (['topic'+str((seq+13)%24)] if j%3==0 else [])
            for t in topics:
                ranks['topic',t] += 1
            if d < 335:
                continue
            k = nvis
            nvis += 1
            token = 'v'+str(k)
            visits.append((token,work,typ,day,hour,'manual',ts,1000+seq%21000,1,'selection_changed'))
            tags.extend((token,t) for t in topics)
            for a in range(2 if k%5==0 else 1):
                key,stage = ACTIONS[(k+a)%2]
                actions.append((day,token,key,'manual',stage,ts+a+1))
                ranks['feature',key] += 1
                ranks['feature_source','manual'] += 1
            if k%10==0:
                for s, stage in enumerate(('generated','dispatched','followup_click')):
                    events.append(('o'+str(k),token,'ai',stage,0,day,ts+s+3))
        acts += len(actions)
        flows += len(events)
        db.execute('INSERT INTO daily VALUES (?,?,?,?,0)',(day,n,len(actions),0))
        db.executemany('INSERT INTO hours VALUES (?,?,?)',((day,h,num) for h,num in hours.items()))
        db.executemany('INSERT INTO work_days VALUES (?,?)',((day,w) for w in sorted(works)))
        db.executemany('INSERT INTO ranks VALUES (?,?,?,?,?)',((day,k,v,v,count) for (k,v),count in ranks.items()))
        db.executemany('INSERT INTO visits VALUES (?,?,?,?,?,?,?,?,?,?)',visits)
        db.executemany('INSERT INTO visit_topics VALUES (?,?)',tags)
        db.executemany('INSERT INTO actions(day,token,feature,source,stage,ts) VALUES (?,?,?,?,?,?)',actions)
        db.executemany('INSERT INTO flow_events(operation_id,token,kind,stage,task,day,ts) VALUES (?,?,?,?,?,?,?)',events)
    assert (nvis, acts, flows) == (20000,24000,6000)
    counts = {t: db.execute('SELECT COUNT(*) FROM '+t).fetchone()[0] for t in
              ('daily','work_days','visits','visit_topics','actions','flow_events')}
    assert counts['daily']==365 and counts['visits']==20000 and counts['actions']+counts['flow_events']==30000
    db.commit()
    db.close()
    return counts


def launch(binary, args, tmp):
    path = tmp / (binary+'.args')
    # Java @argfiles avoid Windows command-line length limits.
    path.write_text('\n'.join('"'+str(s).replace('\\','/').replace('"','\\"')+'"' for s in args),encoding='utf-8')
    cmd = [binary,'@'+str(path)]
    result = subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',errors='replace')
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout


def bench(tmp, cp, name, days=365, profile_dir=None, pressure=False):
    old = name=='baseline'
    legacy = old and BASELINE_DIR is None
    dest = tmp/name
    dest.mkdir()
    shutil.copyfile(tmp/'fixture.db',dest/'usage.db')
    classes = dest/'classes'
    classes.mkdir()
    names = COMMON + ([] if legacy else ['PortraitEvidence','PortraitEvidenceQuery'])
    paths = []
    for n in names:
        path = dest/(n+'.java')
        path.write_bytes(source(n,old))
        paths.append(path)
    # EXPLAIN runs after all timed reads. Only complete, static two-date JOINs here.
    plan_file = dest / 'plans.txt'
    sql = []
    if not old:
        for n in ('LocalAnalytics','PortraitEvidenceQuery'):
            statements = re.findall(r'db\.rawQuery\("([^"]+)"\s*,', source(n,False).decode())
            sql.extend(q for q in statements if ' JOIN ' in q and q.count('?')==2)
        if not sql:
            raise RuntimeError('Expected production join SQL for query plans')
    plan_file.write_text('\n'.join(sql),encoding='utf-8')
    helper = dest/'SnapshotBenchmark.java'
    helper.write_bytes((HERE/'SnapshotBenchmark.java').read_bytes())
    input_hashes = {p.stem:hashlib.sha256(p.read_bytes()).hexdigest() for p in [*paths,helper]}
    launch('javac',['-proc:none','-encoding','UTF-8','-cp',cp,'-d',classes,*paths,helper],dest)
    profile = []
    if profile_dir:
        profile_dir.mkdir(parents=True, exist_ok=True)
        # Whole-JVM diagnostic recording: bootstrap, warmups and later pressure work
        # are included. Attribute query allocations by stack, not total JFR weight.
        profile = ['-XX:StartFlightRecording=filename='+str((profile_dir/(name+'-'+str(days)+'.jfr')).resolve())+',settings=profile,dumponexit=true']
    first_visit = sum(666 + (d % 3 != 0) for d in range(335, max(335, 365-days)))
    expected = dict(views=sum(666 + (d % 3 != 0) for d in range(365-days, 365)),
                    visits=20000-first_visit,
                    features=sum(1+(k % 5 == 0) for k in range(first_visit, 20000)),
                    saved=sum(k % 2 == 0 or k % 5 == 0 for k in range(first_visit, 20000)),
                    copied=sum(k % 2 == 1 or k % 5 == 0 for k in range(first_visit, 20000)))
    checks = ['-Dbench.expected.'+key+'='+str(value) for key,value in expected.items()]
    output = launch('java',['-Xms256m','-Xmx1024m','-Dfile.encoding=UTF-8','-Duser.timezone=UTC',
                            '-Dbench.days='+str(days), '-Dbench.pressure='+str(pressure).lower(), *profile, *checks,
                            '-Dbench.plans='+str(plan_file),'-Dbench.directory='+str(dest),'-Dbench.revision='+('baseline' if legacy else 'current'),
                            '-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore',
                            'com.douai.module.analytics.SnapshotBenchmark'],dest)
    samples = [[float(x) for x in m] for m in re.findall(r'SAMPLE \d+ ([\d.]+) ([\d.]+) ([\d.]+)',output)]
    if len(samples)!=5:
        raise RuntimeError(output)
    return dict(sqlite_plans=[s for s in output.splitlines() if s.startswith(('SQLITE ','PLAN ','STEP '))],samples_ms_peakMiB_deltaMiB=samples,
                source_sha256=input_hashes,
                medians=[round(statistics.median(col),3) for col in zip(*samples)],
                max_sample_ms=max(r[0] for r in samples),max_peak_MiB=max(r[1] for r in samples),
                range_sequence=[s for s in output.splitlines() if s.startswith('RANGE_SEQUENCE ')],
                pressure=[s for s in output.splitlines() if s.startswith('PRESSURE ')])


def main():
    global BASELINE_DIR
    args = argparse.ArgumentParser()
    args.add_argument('--classpath-file',type=Path,help='reuse the testDebugUnitTest classpath (plain text)')
    args.add_argument('--output',type=Path,help='write the report as JSON')
    args.add_argument('--baseline-dir',type=Path,help='compare a preserved source tree instead of the historical commit')
    args.add_argument('--days',type=int,choices=(7,30,365),default=365)
    args.add_argument('--profile-dir',type=Path,help='record measured query allocation samples with desktop JFR')
    args.add_argument('--pressure',action='store_true',help='measure report reads while controlled feature writes are queued')
    options = args.parse_args()
    BASELINE_DIR = options.baseline_dir.resolve() if options.baseline_dir else None
    with tempfile.TemporaryDirectory(prefix='douai-portrait-bench-') as td:
        tmp = Path(td)
        cpfile = options.classpath_file
        if not cpfile:
            cpfile = tmp/'classpath.txt'
            gradle = ROOT / ('gradlew.bat' if os.name=='nt' else 'gradlew')
            subprocess.run([str(gradle),'-q','-I',str(HERE/'classpath.init.gradle'),':app:portraitBenchClasspath'],
                           cwd=ROOT,env={**os.environ,'PORTRAIT_BENCH_CP':str(cpfile)},check=True)
        # Windows locks loaded jars. Keep benchmark JVMs away from concurrent build outputs.
        classpath = []
        for i, entry in enumerate(cpfile.read_text(encoding='utf-8-sig').strip().split(os.pathsep)):
            path=Path(entry)
            if 'unit_test_config_directory' in path.parts:
                continue  # This host-only test has no app resources.
            if path.suffix=='.jar' and path.is_relative_to(ROOT):
                target=tmp/(str(i)+'-'+path.name)
                shutil.copy2(path,target)
                path=target
            classpath.append(str(path))
        cp=os.pathsep.join(classpath)
        counts = fixture(tmp/'fixture.db')
        report = dict(baseline_commit=None if BASELINE_DIR else BASE, baseline_source_dir=str(BASELINE_DIR) if BASELINE_DIR else None,
                      days=options.days,fixture_rows=counts,
                      fixture_sha256=hashlib.sha256((tmp/'fixture.db').read_bytes()).hexdigest())
        for name in ('baseline','current'):
            report[name]=bench(tmp,cp,name,options.days,options.profile_dir,options.pressure)
            print(name,'medians ms/peak MiB/heap growth MiB:',report[name]['medians'],flush=True)
        report['median_change_percent']=round((report['current']['medians'][0]/report['baseline']['medians'][0]-1)*100,1)
        report['query_source_sha256']=hashlib.sha256(source('PortraitEvidenceQuery',False)).hexdigest()
        if options.output:
            options.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()


