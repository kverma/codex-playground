"""Optional REAL LLM extraction bake-off; requires approved OpenAI-compatible model endpoints.

Run ONLY with configured inference; never fall back to mocked extraction.
Compare evidence retrieval, source provenance and Hindsight reflective conclusions.
Outputs are NOT comparable to the no-LLM chunk/episode suite.
"""
import argparse, asyncio, datetime, json, os, time, uuid
from pathlib import Path
from memory_framework_bakeoff import EPISODES, CASES, HARD_CASES, evaluate, save

def credential(name, alternative=None):
    value=os.getenv(name) or (os.getenv(alternative) if alternative else None)
    if not value: raise SystemExit(f"BLOCKED: {name} not configured; no fake-model results will be reported")
    return value

async def graphiti_full():
    from graphiti_core import Graphiti
    from graphiti_core.driver.neo4j_driver import Neo4jDriver
    from graphiti_core.nodes import EpisodeType
    from graphiti_core.embedder.openai import OpenAIEmbedder,OpenAIEmbedderConfig
    from graphiti_core.llm_client.config import LLMConfig
    from graphiti_core.llm_client.openai_generic_client import OpenAIGenericClient
    from graphiti_core.search.search_config_recipes import EDGE_HYBRID_SEARCH_RRF
    llm_model=credential('MEMORY_LLM_MODEL')
    key=credential('MEMORY_LLM_API_KEY','OPENAI_API_KEY')
    base=os.getenv('MEMORY_LLM_BASE_URL') or 'https://api.openai.com/v1'
    embedding_model=credential('MEMORY_EMBEDDING_MODEL')
    embed_key=os.getenv('MEMORY_EMBEDDING_API_KEY',key)
    embed_base=os.getenv('MEMORY_EMBEDDING_BASE_URL') or base
    embed_dim=int(os.getenv('MEMORY_EMBEDDING_DIM','1536'))
    conf=LLMConfig(api_key=key,model=llm_model,small_model=llm_model,base_url=base)
    driver=Neo4jDriver(uri=os.getenv('NEO4J_URI','bolt://localhost:7687'),
                       user=os.getenv('NEO4J_USER','neo4j'),
                       password=os.getenv('NEO4J_PASSWORD','graphiti-password'))
    llm=OpenAIGenericClient(config=conf)
    from graphiti_core.cross_encoder.openai_reranker_client import OpenAIRerankerClient
    g=Graphiti(graph_driver=driver,llm_client=llm,cross_encoder=OpenAIRerankerClient(client=llm,config=conf),
      embedder=OpenAIEmbedder(config=OpenAIEmbedderConfig(api_key=embed_key,
        embedding_model=embedding_model,embedding_dim=embed_dim,base_url=embed_base)),
      max_coroutines=2)
    group='llm-atlas-'+uuid.uuid4().hex[:8]
    private='llm-restricted-'+uuid.uuid4().hex[:8]
    source_map={}
    try:
        await g.build_indices_and_constraints()
        st=time.perf_counter()
        for key,scope,ts,text in EPISODES:
            e=await g.add_episode(name=key,episode_body=text,source=EpisodeType.text,
               source_description='Synthetic incident '+key,
               reference_time=datetime.datetime.fromisoformat(ts.replace('Z','+00:00')),
               group_id=group if scope=='atlas' else private)
            source_map[e.episode.uuid]=key
        ingestion=(time.perf_counter()-st)*1000
        cases=[]
        for case in CASES+HARD_CASES:
            st=time.perf_counter()
            # These are the ACTUAL LLM-extracted entity facts, not the raw episode BM25 layer.
            edges=await g.search(case[1],group_ids=[group],num_results=10)
            passages=[]
            for edge in edges[:5]:
                ids=[source_map[i] for i in edge.episodes if i in source_map]
                passages.append(' '.join(ids)+' '+edge.fact)
            cases.append(evaluate(case,passages,(time.perf_counter()-st)*1000))
        return save('graphiti-llm',cases,ingestion,
          dict(extraction_tested=True,inference_model=llm_model,embedding_model=embedding_model,
               scope='Graphiti extracted-relationship evidence; no reflection'))
    finally: await g.close()

def hindsight_full():
    from hindsight_client import Hindsight
    credential('MEMORY_LLM_API_KEY','OPENAI_API_KEY')
    api=Hindsight(base_url=os.getenv('HINDSIGHT_URL','http://localhost:8888'),timeout=240.0)
    bank='llm-atlas-'+uuid.uuid4().hex[:8]
    other='llm-restricted-'+uuid.uuid4().hex[:8]
    api.create_bank(bank_id=bank,name='LLM extraction memory bake-off')
    api.create_bank(bank_id=other,name='Restricted bank')
    api.update_bank_config(bank,retain_extraction_mode='concise')
    api.update_bank_config(other,retain_extraction_mode='concise')
    st=time.perf_counter()
    for key,scope,ts,text in EPISODES:
        api.retain(bank_id=bank if scope=='atlas' else other,content=text,
            document_id=key,context='Synthetic incident '+key,timestamp=ts,
            retain_async=False)
    ingestion=(time.perf_counter()-st)*1000
    cases=[]
    for case in CASES+HARD_CASES:
        st=time.perf_counter()
        result=api.recall(bank_id=bank,query=case[1],budget='mid',
                           max_tokens=4096,include_chunks=True,max_chunk_tokens=512)
        texts=[]
        for x in result.results[:5]:
            chunk=(result.chunks or {}).get(x.chunk_id) if x.chunk_id else None
            texts.append((chunk.text if chunk else x.text))
        cases.append(evaluate(case,texts,(time.perf_counter()-st)*1000))
    # Reflection is a different task from retrieval; preserve its raw response for human evaluation.
    reflections=[]
    for query in [
        'What diagnostic procedure has repeatedly worked for ConsumerC Kafka lag and why?',
        'Which team owns ConsumerC now? Distinguish verified changes from rumors.',
    ]:
        st=time.perf_counter()
        answer=api.reflect(bank_id=bank,query=query,budget='low')
        reflections.append(dict(question=query,answer=answer.text,elapsed_ms=round((time.perf_counter()-st)*1000,3)))
    return save('hindsight-llm',cases,ingestion,
        dict(extraction_tested=True,reflection_tested=True,reflections=reflections,
          service_version=str(api.get_version().api_version)))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('backend',choices=['hindsight','graphiti'])
    a=p.parse_args()
    result=hindsight_full() if a.backend=='hindsight' else asyncio.run(graphiti_full())
    assert result['case_count']==len(CASES)+len(HARD_CASES)
