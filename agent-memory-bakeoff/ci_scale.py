"""RDF engine scale bake-off: identical N-Quads, 1x/10x/50x/100x."""
import gc,json,os,tempfile,time
from pathlib import Path
from rdflib import Dataset
from pyoxigraph import Store,RdfFormat
import ci_smoke as cases

def grow(scale):
    parts=[cases.NQ]
    for i in range(1,scale):
        parts.append(cases.NQ.replace('urn:test:a/','urn:test:a/copy'+str(i)+'/')
          .replace('urn:test:g/','urn:test:g/copy'+str(i)+'/')
          .replace('urn:test:atlas',f'urn:test:tenant_{i}')
          .replace('urn:test:Svc',f'urn:test:Svc_{i}')
          .replace('urn:test:Topic',f'urn:test:Topic_{i}'))
    return ''.join(parts)

def run(engine,scale,nq,path):
    start=time.perf_counter_ns()
    if engine=='rdflib':
        db=Dataset();db.parse(data=nq,format='nquads')
    else:
        db=Store(path);db.load(input=nq,format=RdfFormat.N_QUADS)
    ingest=round((time.perf_counter_ns()-start)/1e6,3)
    result=cases.check(db,engine,reps=5)
    db=None;gc.collect()
    return {'engine':engine,'scale':scale,'quads':nq.count('\n'),'ingest_ms':ingest,
      'cases':result,'passed':all(c['pass'] for c in result)}

def main():
    results=[]
    with tempfile.TemporaryDirectory() as tmp:
        for scale in (1,10,50,100):
            nq=grow(scale)
            for engine in ('rdflib','oxigraph'):
                result=run(engine,scale,nq,os.path.join(tmp,f'rocksdb-{scale}'))
                results.append(result)
                print(f"{engine} scale={scale} quads={result['quads']} ingest={result['ingest_ms']}ms pass={result['passed']}",flush=True)
                assert result['passed']
    Path('results').mkdir(exist_ok=True)
    Path('results/ci-scale.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':main()
