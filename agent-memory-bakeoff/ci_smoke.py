"""Real RDF engine parity and persistent reopen smoke test; synthetic facts only."""
import gc, json, os, statistics, tempfile, time
from pathlib import Path
from rdflib import Dataset
from pyoxigraph import Store, RdfFormat

E='urn:test:'
BASE='PREFIX e: <urn:test:> PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n'
# id, subject, predicate, object, start, end, trust, tenant
ROWS=[
('a1','Svc','owner','OldTeam','2026-01-01','2026-10-11','ok','atlas'),
('a2','Svc','owner','NewTeam','2026-10-11',None,'ok','atlas'),
('a3','Svc','owner','OldTeam','2026-10-12',None,'rumor','atlas'),
('a4','Svc','consumes','Topic','2026-01-01',None,'ok','atlas'),
('a5','Topic','depends','Billing','2026-01-01',None,'ok','atlas'),
('a6','Secret','owner','Forbidden','2026-01-01',None,'ok','other'),
('a7','INC1','fix','retry','2026-09-01',None,'ok','atlas'),
('a8','INC1','outcome','success','2026-09-01',None,'ok','atlas'),
('a9','INC2','fix','retry','2026-09-02',None,'ok','atlas'),
('a10','INC2','outcome','success','2026-09-02',None,'ok','atlas'),
('a11','INC3','fix','increase_pool','2026-09-03',None,'ok','atlas'),
('a12','INC3','outcome','failure','2026-09-03',None,'ok','atlas')]
def t(x):return f'<{E}{x}>'
def lit(x):return f'"{x}T00:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>'
quads=[]
for id,s,p,o,start,end,status,tenant in ROWS:
    a=t('a/'+id);g=t('g/'+id)
    props=[('sub',t(s)),('pred',t(p)),('obj',t(o)),('from',lit(start)),
      ('status',t(status)),('tenant',t(tenant)),('source',t('source/'+id))]
    if end:props.append(('to',lit(end)))
    for k,v in props:quads.append(f'{a} {t(k)} {v} {g} .')
NQ='\n'.join(quads)+'\n'
TEMPLATE='''SELECT ?v WHERE { GRAPH ?g {
 ?a e:sub e:Svc ; e:pred e:owner ; e:obj ?v ; e:from ?start ;
    e:status e:ok ; e:tenant e:atlas .
 OPTIONAL { ?a e:to ?finish . }}
 FILTER(?start <= "{date}"^^xsd:dateTime)
 FILTER(!BOUND(?finish) || ?finish > "{date}"^^xsd:dateTime) }'''
QUERIES={
'owner-now':(BASE+TEMPLATE.replace('{date}','2026-10-18T00:00:00Z'),['NewTeam']),
'owner-before':(BASE+TEMPLATE.replace('{date}','2026-10-01T00:00:00Z'),['OldTeam']),
'multi-hop':(BASE+'''SELECT ?v WHERE {
 GRAPH ?g1 { ?a e:sub e:Svc ; e:pred e:consumes ; e:obj ?topic ;
                e:status e:ok ; e:tenant e:atlas .}
 GRAPH ?g2 { ?b e:sub ?topic ; e:pred e:depends ; e:obj ?v ;
                e:status e:ok ; e:tenant e:atlas .}}''',['Billing']),
'tenant-block':(BASE+'''SELECT ?v WHERE { GRAPH ?g {
 ?a e:sub e:Secret ; e:obj ?v ; e:tenant e:atlas . }}''',[]),
'verified-success':(BASE+'''SELECT ?incident WHERE {
 GRAPH ?g1 { ?a e:sub ?incident ; e:pred e:fix ; e:obj e:retry ;
                e:status e:ok ; e:tenant e:atlas .}
 GRAPH ?g2 { ?b e:sub ?incident ; e:pred e:outcome ; e:obj e:success ;
                e:status e:ok ; e:tenant e:atlas .}}''',['INC1','INC2']),
'source-evidence':(BASE+'''SELECT ?v WHERE { GRAPH ?g {
 ?a e:sub e:INC1 ; e:pred e:fix ; e:source ?v ; e:status e:ok ;
    e:tenant e:atlas .}}''',['source/a7']),
}
def check(db,kind,reps=20):
    out=[]
    for name,(q,expected) in QUERIES.items():
        def run():
            if kind=='oxigraph':
                return sorted(v.value.replace(E,'') for row in db.query(q)
                              for v in row if v is not None)
            return sorted(str(v).replace(E,'') for row in db.query(q)
                          for v in row if v is not None)
        actual=run()
        samples=[]
        for _ in range(reps):
            st=time.perf_counter_ns();run();samples.append((time.perf_counter_ns()-st)/1e6)
        out.append({'case':name,'pass':actual==expected,'expected':expected,
                    'actual':actual,'median_ms':round(statistics.median(samples),3)})
    return out
def main():
    output={'assertions':len(ROWS),'quads':len(quads),'backends':{}}
    with tempfile.TemporaryDirectory() as d:
        st=time.perf_counter_ns();r=Dataset();r.parse(data=NQ,format='nquads')
        ing=round((time.perf_counter_ns()-st)/1e6,3)
        output['backends']['rdflib']={'ingest_ms':ing,'cases':check(r,'rdflib')}
        r.close()
        st=time.perf_counter_ns();path=os.path.join(d,'rocksdb')
        s=Store(path);s.load(input=NQ,format=RdfFormat.N_QUADS)
        ing=round((time.perf_counter_ns()-st)/1e6,3)
        cases=check(s,'oxigraph')
        s=None;gc.collect();s=Store(path)
        reopened=check(s,'oxigraph',1);s=None
        output['backends']['oxigraph']={'ingest_ms':ing,'cases':cases,
          'reopen_pass':all(x['pass'] for x in reopened)}
    Path('results').mkdir(exist_ok=True)
    Path('results/ci-smoke.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))
    assert all(z['pass'] for e in output['backends'].values() for z in e['cases'])
    assert output['backends']['oxigraph']['reopen_pass']
if __name__=='__main__':main()
