"""No-LLM evidence retrieval comparison using REAL Graphiti/FalkorDB and Hindsight.

Graphiti: native EpisodicNode.save + Graphiti.search_ BM25. Hindsight: chunks
retain + recall. Does NOT measure LLM extraction, reflection, or skill learning.
"""
import argparse, asyncio, datetime, json, os, re, statistics, time, uuid
from pathlib import Path

EPISODES=[
 ("INC-101","atlas","2026-09-18T09:00:00Z","INC-101: Atlas Kafka ConsumerC lag was caused by Billing API HTTP 429 rate limiting. Increasing consumer concurrency failed to resolve the lag."),
 ("INC-102","atlas","2026-09-21T10:00:00Z","INC-102: Atlas Kafka ConsumerC backlog was resolved by reducing Billing API upstream throttle pressure. Retry configuration remained unchanged."),
 ("OPS-001","atlas","2026-09-27T10:00:00Z","OPS-001: Old incident runbook recommended increasing consumer concurrency before inspecting downstream dependency latency."),
 ("OPS-002","atlas","2026-10-12T10:00:00Z","OPS-002: Revised incident runbook replaces OPS-001; inspect Billing API p95 latency and throttling before changing Kafka consumer concurrency."),
 ("OWN-001","atlas","2026-09-30T10:00:00Z","OWN-001: Team LegacyPricing owned service ConsumerC until October 10, 2026."),
 ("OWN-002","atlas","2026-10-11T10:00:00Z","OWN-002: Team PlatformPricing assumed ownership of service ConsumerC on October 11, 2026, replacing LegacyPricing."),
 ("INC-103","atlas","2026-10-15T12:00:00Z","INC-103: On-call engineer confirmed Billing API backpressure. Throttle relief restored ConsumerC throughput; blindly adding partitions was not helpful."),
 ("NOTE-001","atlas","2026-10-16T14:00:00Z","NOTE-001: Unverified hallway rumor says team LegacyPricing still owns ConsumerC. This claim is not trusted."),
 ("SEC-001","restricted","2026-10-16T15:00:00Z","SEC-001: RESTRICTED tenant has a confidential service token REDACTED-DO-NOT-RETRIEVE."),
]
CASES=[
 ("causal","Billing API HTTP 429 Kafka ConsumerC lag","INC-101"),
 ("successful-mitigation","INC-102 reducing upstream throttle pressure resolved backlog","INC-102"),
 ("current-runbook","OPS-002 revised incident runbook Billing latency","OPS-002"),
 ("historical-owner","LegacyPricing ConsumerC owner until October 10","OWN-001"),
 ("new-owner","PlatformPricing ConsumerC ownership October 11","OWN-002"),
 ("failed-procedure","INC-101 increasing consumer concurrency failed","INC-101"),
 ("recurring-pattern","INC-103 backpressure throttle relief partitions","INC-103"),
]
HARD_CASES=[
 ("cause-paraphrase","Which dependency caused rate limiting and an accumulating Kafka backlog?","INC-101"),
 ("remedy-paraphrase","Which outage was resolved by reducing throttle pressure without changing retries?","INC-102"),
 ("playbook-paraphrase","What revised procedure checks the downstream p95 before adding consumer capacity?","OPS-002"),
 ("historical-owner-no-id","Which engineering team operated this service before the ownership handoff?","OWN-001"),
 ("current-owner-no-id","Which engineering team assumed responsibility after the mid-October transfer?","OWN-002"),
 ("failed-action-no-id","Which episode proved increasing concurrency did not resolve the queue lag?","INC-101"),
 ("last-incident-no-id","Which recent incident demonstrated that adding partitions was unhelpful?","INC-103"),
 ("untrusted-claim","Which unverified statement contradicts the ownership transfer?","NOTE-001"),
]
for i in range(int(os.getenv('MEMORY_DISTRACTORS','0'))):
    kind=i % 3
    if kind == 0:
        msg=f'SIM-{i:04d}: Kafka Consumer{i+1000} backlog resulted from Billing API HTTP 429 throttling; raising consumer concurrency did not fix it.'
    elif kind == 1:
        msg=f'SIM-{i:04d}: Team OtherPlatform{i} owns Consumer{i+1000}; transfer from LegacyService{i} occurred in October 2026.'
    else:
        msg=f'SIM-{i:04d}: Operations runbook for Consumer{i+1000} advises checking Billing API p95 and then reducing throttle pressure.'
    EPISODES.append((f'SIM-{i:04d}','atlas','2026-10-13T10:00:00Z',msg))
ID_RE=re.compile(r'^(?:(?:INC|OWN|OPS|NOTE|SEC)-\d{3}|SIM-\d{4})')
def evaluate(case,texts,elapsed_ms):
    name,query,expected=case
    ids=[]
    for text in texts[:5]:
        match=ID_RE.match(text)
        if match: ids.append(match.group())
    rank=next((i+1 for i,k in enumerate(ids) if k==expected),None)
    return dict(case=name,query=query,expected=expected,observed_ids=ids,
                first_hit_rank=rank,hit_at_5=rank is not None,elapsed_ms=round(elapsed_ms,3))
def save(engine,results,ingest_ms,extra):
    hits=sum(c['hit_at_5'] for c in results)
    hard=[c for c in results if c['case'] in {x[0] for x in HARD_CASES}]
    result=dict(engine=engine,mode='real_backend_no_llm',episode_count=len(EPISODES),distractor_count=int(os.getenv('MEMORY_DISTRACTORS','0')),
      case_count=len(CASES)+len(HARD_CASES),hit_at_5=hits,hit_at_5_rate=round(hits/(len(CASES)+len(HARD_CASES)),3),
      hard_hit_at_5=sum(c['hit_at_5'] for c in hard),hard_case_count=len(hard),
      ingest_ms=round(ingest_ms,3),median_query_ms=round(statistics.median(c['elapsed_ms'] for c in results),3),
      cases=results,**extra)
    Path('results').mkdir(exist_ok=True)
    Path('results/'+engine+'-no-llm.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)
    return result
async def graphiti():
    from graphiti_core import Graphiti
    from graphiti_core.driver.neo4j_driver import Neo4jDriver
    from graphiti_core.nodes import EpisodicNode,EpisodeType
    from graphiti_core.embedder.client import EmbedderClient
    from graphiti_core.cross_encoder.client import CrossEncoderClient
    from graphiti_core.llm_client.config import LLMConfig
    from graphiti_core.llm_client.openai_generic_client import OpenAIGenericClient
    from graphiti_core.search.search_config import SearchConfig,EpisodeSearchConfig,EpisodeSearchMethod,EpisodeReranker
    class OfflineEmbed(EmbedderClient):
        async def create(self,input_data): return [0.0]*1024
        async def create_batch(self,input_data_list): return [[0.0]*1024 for _ in input_data_list]
    class OfflineCross(CrossEncoderClient):
        async def rank(self,query,passages): return [(p,0.0) for p in passages]
    driver=Neo4jDriver(uri=os.getenv('NEO4J_URI','bolt://localhost:7687'),user=os.getenv('NEO4J_USER','neo4j'),password=os.getenv('NEO4J_PASSWORD','graphiti-password'))
    agent=Graphiti(graph_driver=driver,
        llm_client=OpenAIGenericClient(config=LLMConfig(api_key='unused',model='unused',small_model='unused',base_url='http://127.0.0.1:1/v1')),
        embedder=OfflineEmbed(),cross_encoder=OfflineCross())
    group='atlas_'+uuid.uuid4().hex[:8]
    other='restricted_'+uuid.uuid4().hex[:8]
    try:
        await agent.build_indices_and_constraints()
        st=time.perf_counter()
        for key,scope,ts,content in EPISODES:
            node=EpisodicNode(name=key,group_id=group if scope=='atlas' else other,
                source=EpisodeType.text,source_description='Synthetic incident event',
                content=content,valid_at=datetime.datetime.fromisoformat(ts.replace('Z','+00:00')))
            await node.save(driver)
        ingest=(time.perf_counter()-st)*1000
        config=SearchConfig(episode_config=EpisodeSearchConfig(
          search_methods=[EpisodeSearchMethod.bm25],reranker=EpisodeReranker.rrf),limit=5)
        async def query(text):
            return await agent.search_(text,config=config,group_ids=[group],query_vector=[0.0]*1024)
        results=[]
        for case in CASES+HARD_CASES:
            st=time.perf_counter()
            retrieved=await query(case[1])
            results.append(evaluate(case,[e.name+' '+e.content for e in retrieved.episodes],
                (time.perf_counter()-st)*1000))
        private=await query('REDACTED-DO-NOT-RETRIEVE')
        safe=not any('SEC-001'==e.name for e in private.episodes)
        return save('graphiti',results,ingest,dict(restricted_group_excluded=safe,extraction_tested=False))
    finally:
        await agent.close()
def hindsight():
    from hindsight_client import Hindsight
    api=Hindsight(base_url=os.getenv('HINDSIGHT_URL','http://localhost:8888'),timeout=240.0)
    bank='poc-atlas-'+uuid.uuid4().hex[:8];other='poc-restricted-'+uuid.uuid4().hex[:8]
    api.create_bank(bank_id=bank,name='Synthetic Atlas incidents')
    api.create_bank(bank_id=other,name='Synthetic confidential content')
    st=time.perf_counter()
    for key,scope,ts,content in EPISODES:
        api.retain(bank_id=bank if scope=='atlas' else other,content=content,
            document_id=key,context='Synthetic incident event',timestamp=ts,retain_async=False)
    ingest=(time.perf_counter()-st)*1000
    results=[]
    for case in CASES+HARD_CASES:
        st=time.perf_counter()
        recalled=api.recall(bank_id=bank,query=case[1],max_tokens=4096,budget='mid')
        results.append(evaluate(case,[r.text for r in recalled.results],(time.perf_counter()-st)*1000))
    private=api.recall(bank_id=bank,query='REDACTED-DO-NOT-RETRIEVE',max_tokens=1024)
    safe=not any('SEC-001' in r.text for r in private.results)
    return save('hindsight',results,ingest,dict(restricted_bank_excluded=safe,
        extraction_tested=False,retain_mode_required='chunks',service_version=str(api.get_version().api_version)))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('backend',choices=['hindsight','graphiti'])
    a=p.parse_args()
    result=hindsight() if a.backend=='hindsight' else asyncio.run(graphiti())
    assert result['case_count']==len(CASES)+len(HARD_CASES)
    assert result.get('restricted_bank_excluded',result.get('restricted_group_excluded'))
    assert result['hit_at_5'] > 0, 'No evidence retrieved: fail the job instead of reporting a false success'
