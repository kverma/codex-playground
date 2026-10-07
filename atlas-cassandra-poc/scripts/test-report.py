#!/usr/bin/env python3
"""Consolidate real JUnit outcomes and captured operands; never invent actual values."""
import argparse
import base64
import csv
import gzip
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
STYLE = '''body{font:16px/1.5 system-ui;max-width:1200px;margin:40px auto;padding:0 24px;color:#182d3a}
table{border-collapse:collapse;width:100%}th,td{text-align:left;vertical-align:top;border:1px solid #ccd6dd;padding:10px}
th{background:#edf3f6}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f6f8;padding:14px}
td{overflow-wrap:anywhere}a{color:#075ca3}small{color:#52636f}details{margin:12px 0}input{padding:12px;width:90%}'''

def esc(x):
    return html.escape(str(x))

def page(title, body):
    return f'<!doctype html><meta charset="utf-8"><title>{esc(title)}</title><style>{STYLE}</style><h1>{esc(title)}</h1>{body}'

def method_source(text, method):
    # Java tokens: skip comments and literals when balancing method braces.
    match = re.search(r'\bvoid\s+' + re.escape(method) + r'\s*\([^)]*\)[^{]*\{', text)
    if not match:
        return 'Method source unavailable; see complete source and raw evidence.'
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', text[match.end()-1:], re.S):
        if token[0] == '{': depth += 1
        if token[0] == '}': depth -= 1
        if depth == 0:
            return text[match.start():match.end()-1+token.end()]
    return text[match.start():]

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('artifacts', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--run-url', required=True)
    p.add_argument('--commit', required=True)
    p.add_argument('--job-results', default='{}')
    p.add_argument('--require-traces', action='store_true')
    args = p.parse_args()
    if args.output.resolve() == args.artifacts.resolve() or args.artifacts.resolve() in args.output.resolve().parents:
        p.error('Output must be outside the input artifacts.')
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    raw = out/'raw'
    shutil.copytree(args.artifacts, raw, dirs_exist_ok=True)
    shutil.copytree(ROOT/'src', out/'source', dirs_exist_ok=True)
    shutil.copytree(ROOT/'maintainer', out/'maintainer-source', dirs_exist_ok=True)
    with (ROOT/'docs/test-scenarios.tsv').open() as f:
        catalog = {f'AT-{i:03}': row for i,row in enumerate(csv.DictReader(f, delimiter='\t'),1)}
    sources = {}
    for path in sorted((ROOT/'src').rglob('*.java')):
        text = path.read_text()
        for method in re.findall(r'@Test[^\n]*?\bvoid\s+(\w+)\(', text):
            sources[method] = (method_source(text,method), 'source/'+str(path.relative_to(ROOT/'src')))
    upstream = {}
    for line in (ROOT/'docs/upstream-test-scenarios.md').read_text().splitlines():
        cells = [s.strip() for s in line.strip('|').split('|')]
        if len(cells) == 3 and re.fullmatch(r'`\w+`', cells[-1]):
            upstream[cells[-1].strip('`')] = cells[:2]
    # Logs retain exact operands from the separate pinned JDK11 harness.
    upstream_events = {}
    for log in raw.rglob('*.log'):
        if log.name not in ('cas.log','cas-write.log','atlas-batch.log'): continue
        key = None
        for line in log.read_text(errors='replace').splitlines():
            if 'ATLAS_CASE ' in line:
                key = line.split('ATLAS_CASE ',1)[1].strip()
            if key and ('ATLAS_ROWS expected=' in line or 'ATLAS_CHECK operation=' in line):
                m = re.search(r'expected=(\S+) actual=(\S+)',line)
                if m:
                    upstream_events.setdefault(key,[]).append({'kind':'harness comparison','expected':base64.b64decode(m[1]).decode(), 'actual':base64.b64decode(m[2]).decode()})
            elif key and re.search(r'ATLAS_(SAMPLE|RETRY|PHASE|SPLIT_PRUNE|REPAIR)',line):
                upstream_events.setdefault(key,[]).append({'kind':'harness observation','actual':line.strip()})
    executions = []
    gaps = []
    for xml in sorted(raw.rglob('TEST-*.xml')):
        tree = ET.parse(xml)
        for case in tree.iter('testcase'):
            name, cls = case.get('name',''),case.get('classname','')
            sid = re.match(r'AT-\d+',name)
            sid = sid[0] if sid else None
            meta = catalog.get(sid,{})
            method = meta.get('method',name.split('(')[0])
            status = 'PASSED'
            for tag, label in [('skipped','SKIPPED'),('failure','FAILED'),('error','ERROR')]:
                if case.find(tag) is not None: status=label
            event_counts, preview = {}, []
            trace_name = cls+'--'+method+'.jsonl.gz'
            # Match the same artifact/job; shallow and grade repeat model cases.
            job_root = raw/xml.relative_to(raw).parts[0]
            found = list(job_root.rglob(trace_name))
            trace = found[0] if found else None
            if trace:
                with gzip.open(trace,'rt') as f:
                    for line in f:
                        event = json.loads(line)
                        kind = event['kind']
                        event_counts[kind] = event_counts.get(kind,0)+1
                        if len(preview)<200: preview.append(event)
            else:
                preview = upstream_events.get(cls.split('.')[-1]+'.'+method,[])
                for event in preview:
                    event_counts[event['kind']]=event_counts.get(event['kind'],0)+1
                if cls.startswith('atlas.poc.') and status != 'SKIPPED': gaps.append(cls+'.'+method)
            snippet, source_link = sources.get(method,('Source unavailable',''))
            if not sid:
                files = list(job_root.rglob(cls.split('.')[-1]+'.java'))
                if files:
                    snippet = method_source(files[0].read_text(),method)
                    source_link = str(files[0].relative_to(out))
            objective = meta.get('goal',upstream.get(method,[method,''])[0])
            expected = meta.get('outcome',upstream.get(method,['','See source assertions for the required outcome.'])[1])
            row = {'scenario':sid,'class':cls,'method':method,'name':name,'objective':objective,
                   'expected':expected,'status':status,'seconds':case.get('time'),
                   'xml':str(xml.relative_to(out)),'trace':str(trace.relative_to(out)) if trace else None,
                   'event_counts':event_counts,'job':xml.relative_to(raw).parts[0]}
            row['page']=f'case-{len(executions)+1:04}.html'
            executions.append(row)
            body = f'<p><a href="index.html">All cases</a> · <b>{status}</b> · {esc(row["job"])} · {esc(cls)}</p>'
            body += f'<h2>Objective</h2><p>{esc(objective)}</p><h2>Expected outcome</h2><p>{esc(expected)}</p>'
            body += '<h2>Sample data and operation</h2><p>The fixture below is the executed test source. Prices are integer cents: 500 = $5.00, 600 = $6.00. “churned=true” includes churned customers. UUIDs identify a subject, operation or concurrency guard; they are not prices or dates. Generated cases use seeds/loops shown here; actual operands appear below.</p>'
            body += f'<details><summary>Show executable sample data and operations</summary><pre>{esc(snippet)}</pre></details>'
            if source_link: body += f'<p><a href="{esc(source_link)}">Complete fixture source, including helpers</a></p>'
            body += '<h2>Change as DML / operation trace</h2><p>CQL request events show the actual statement template and positional parameters passed to Cassandra. Model cases execute Java operations above and have no executed DML. Child-JVM operations are in the preserved protocol histories; their CQL is not attributed to the parent test unless captured there.</p>'
            body += '<h2>Expected output and actual output</h2><p>Values below were evaluated during execution. PASS/FAIL on an assertion means the JUnit comparison returned/threw; a failed comparison can be intentionally caught by a negative-control test. The case outcome comes from JUnit XML. Boolean checks report the evaluated predicate; see the source location for its meaning.</p>'
            body += f'<p>Captured event counts: {esc(json.dumps(event_counts))}. Showing the first {len(preview)} events; the complete compressed trace and all raw histories are preserved.</p>'
            if trace: body += f'<p><a href="{esc(row["trace"])}">Complete assertion and DML trace (.jsonl.gz)</a></p>'
            if not preview: body += '<p><b>No value-level capture available for this execution.</b> Only the JUnit outcome and linked original evidence are available; no actual row values have been inferred.</p>'
            body += '<table><tr><th>Operation / location</th><th>Expected output</th><th>Actual output / DML</th><th>Comparison</th></tr>'
            for event in preview:
                actual = event.get('actual','')
                if event['kind'].startswith('cql'): actual=json.dumps(event,indent=2)
                body += '<tr>'+''.join('<td><pre>'+esc(v)+'</pre></td>' for v in [event.get('operation',event['kind'])+' '+event.get('site',''),event.get('expected',''),actual,event.get('comparison','')])+'</tr>'
            body += '</table>'
            detail = '\n'.join(ET.tostring(e,encoding='unicode') for e in case if e.tag in ('failure','error','skipped'))
            body += f'<p><a href="{esc(row["xml"])}">Original JUnit result</a> · <a href="evidence-index.html">All raw traces</a></p><pre>{esc(detail)}</pre>'
            (out/row['page']).write_text(page((sid+' — ' if sid else '')+objective,body))
    manifest=[]
    for path in sorted(raw.rglob('*')):
        if path.is_file():
            digest=hashlib.sha256()
            with path.open('rb') as f:
                for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
            manifest.append({'path':str(path.relative_to(out)),'bytes':path.stat().st_size,'sha256':digest.hexdigest()})
    (out/'evidence-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    evidence='<p>Every supplied artifact file is retained. These are original observations, source fixtures, logs and independent-checker histories; generated negative controls are not production failures.</p><ul>'
    evidence+=''.join(f'<li><a href="{esc(m["path"])}">{esc(m["path"])}</a> ({m["bytes"]} bytes)</li>' for m in manifest)+'</ul>'
    (out/'evidence-index.html').write_text(page('Complete trace inventory',evidence))
    distinct={}
    priority={'PASSED':0,'SKIPPED':1,'FAILED':2,'ERROR':3}
    for e in executions:
        key=e['class']+'::'+e['method']
        if key not in distinct or priority[e['status']]>priority[distinct[key]]: distinct[key]=e['status']
    counts={s:list(distinct.values()).count(s) for s in priority}
    seen={e['scenario'] for e in executions}
    missing=[sid for sid in catalog if sid not in seen]
    summary={'run_url':args.run_url,'commit':args.commit,'jobs':json.loads(args.job_results),
             'distinct_tests':len(distinct),'executions':len(executions),'outcomes':counts,
             'unexecuted_scenarios':missing,'missing_atlas_traces':gaps,'raw_files':len(manifest),
             'cases':executions,'scope':'Bounded PoC evidence only; PG-COMMIT, PG-CASS and production E2E remain unproven.'}
    (out/'report.json').write_text(json.dumps(summary,indent=2)+'\n')
    body=f'<p><a href="{esc(args.run_url)}">GitHub Actions run</a> · commit <code>{esc(args.commit)}</code></p><p><b>{len(distinct)} distinct tests; {len(executions)} executions.</b> {esc(counts)}</p>'
    body+='<p>Repeated model runs are listed separately but counted once in the distinct total. Missing jobs and unexecuted scenarios are never treated as passes. This report consolidates test objectives, executable sample data, real captured DML, expected/actual operands, and all supplied raw evidence.</p>'
    body+=f'<p>Job conclusions: {esc(args.job_results)}. Unexecuted scenario IDs: {esc(", ".join(missing) or "none")}. Atlas executions missing value traces: {len(gaps)}.</p>'
    body+='<p><b>Bounded PoC only. PG-COMMIT, PG-CASS and production end-to-end qualification remain unproven.</b></p><p><a href="evidence-index.html">All raw traces</a> · <a href="evidence-manifest.json">SHA-256 inventory</a> · <a href="report.json">Machine-readable report</a></p>'
    body+='<p><input id="filter" placeholder="Filter by scenario, objective, class, job or outcome" oninput="document.querySelectorAll(\'tbody tr\').forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(this.value.toLowerCase()))"></p><table><thead><tr><th>Scenario / objective</th><th>Execution</th><th>Outcome</th><th>Captured evidence</th></tr></thead><tbody>'
    for e in executions:
        body+=f'<tr><td><a href="{e["page"]}">{esc(e["scenario"] or e["method"])} — {esc(e["objective"])}</a></td><td>{esc(e["job"])}<br>{esc(e["class"])}</td><td>{e["status"]}</td><td>{esc(e["event_counts"])}</td></tr>'
    for sid in missing:body+=f'<tr><td>{sid} — {esc(catalog[sid]["goal"])}</td><td>No supplied execution</td><td>NOT RUN</td><td>None</td></tr>'
    body+='</tbody></table>'
    (out/'index.html').write_text(page('Atlas test evidence report',body))
    (out/'README.md').write_text(f'# Atlas test evidence report\n\nOpen `index.html` after extracting the entire bundle.\n\nRun: {args.run_url}\n\nCommit: `{args.commit}`\n\n{len(distinct)} distinct tests, {len(executions)} executions. Outcomes: {counts}.\n\nUnexecuted scenarios: {missing}. Missing Atlas value traces: {len(gaps)}.\n\nThe `raw/` directory retains every supplied artifact; `evidence-manifest.json` records checksums. The HTML previews the first 200 events per execution. Complete events remain in compressed JSONL traces. Source fixtures show the sample inputs and model operations; CQL events contain actual templates and bound values. No absent output is inferred from a pass.\n\nPG-COMMIT, PG-CASS and production E2E remain unproven.\n')
    print(json.dumps({k:v for k,v in summary.items() if k!='cases'},indent=2))
    if args.require_traces and (gaps or not executions): raise SystemExit('Report saved, but required execution traces are missing.')

if __name__=='__main__': main()
