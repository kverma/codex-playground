#!/usr/bin/env python3
import base64
import hashlib
import json
from pathlib import Path
import sys

def load(path): return json.loads(path.read_text())
def certificate(cut):
    fields = ['archive-cut-v1',str(cut['owner']),str(cut['fence']),str(cut['sequence']),str(cut['cents'])]
    body = ''.join(':'.join(str(r[k]) for k in ['sequence','operation','before','after'])+';' for r in cut['receipts'])
    return {k:cut[k] for k in ['owner','fence','sequence','cents']} | {'digest':hashlib.sha256(('|'.join(fields)+'|'+body).encode()).hexdigest()}

def replay(frames):
    owner=epoch=generation=floor=0; cents=500; active=True; root=base=None
    ledger=[]; objects={}; tickets={}; reads={}; cuts={}; verified=set(); certified=set(); pruned={}; installed={}
    for index,frame in enumerate(frames):
        c=frame['command']; actor=c['actor']; step=c['step']
        if step=='READ':
            expected='OK' if active else 'FENCED'
            if active: reads[actor]=generation
        elif step=='WRITE':
            expected='NOT_READY' if actor not in reads else 'FENCED' if not active else 'STALE' if reads[actor]!=generation else 'OK'
            if expected=='OK':
                ledger.append(dict(sequence=len(ledger)+1,operation=actor,before=cents,after=400+actor)); cents=400+actor; generation+=1
        elif step=='START':
            owner+=1; tickets[actor]=owner; expected='OK'
        elif step=='FREEZE':
            expected='NOT_READY' if actor not in tickets else 'STALE' if tickets[actor]<=epoch else 'OK'
            if expected=='OK':
                epoch=tickets[actor];generation+=1;active=False
                cuts[actor]=dict(owner=epoch,fence=generation,sequence=len(ledger),cents=cents,receipts=list(ledger))
        elif step=='COPY':
            expected='NOT_READY' if actor not in cuts else 'EXISTS' if str(tickets[actor]) in objects else 'OK'
            if expected=='OK':
                value=dict(cuts[actor]);value['receipts']=list(value['receipts'])
                if c['copy']=='OMIT_TAIL' and value['receipts']:value['receipts'].pop()
                if c['copy']=='WRONG_FENCE':value['fence']-=1
                if c['copy']=='WRONG_PRICE':value['cents']+=1
                objects[str(tickets[actor])]=value
        elif step=='VERIFY':
            expected='NOT_READY' if actor not in cuts or str(tickets[actor]) not in objects else 'COVERAGE_MISMATCH' if objects[str(tickets[actor])]!=cuts[actor] else 'OK'
            if expected=='OK':verified.add(actor)
        elif step=='LOSE_ARCHIVE':
            expected='NOT_READY' if actor not in tickets or str(tickets[actor]) not in objects else 'OK'
            if expected=='OK':del objects[str(tickets[actor])]
        elif step=='CERTIFY':
            expected='NOT_READY' if actor not in verified else 'STALE' if tickets[actor]!=owner else 'OK'
            if expected=='OK':root=certificate(cuts[actor]);certified.add(actor)
        elif step=='PRUNE':
            expected='NOT_READY' if actor not in certified else 'STALE' if active or epoch!=tickets[actor] or generation!=cuts[actor]['fence'] else 'OK'
            if expected=='OK':floor=cuts[actor]['sequence'];base=certificate(cuts[actor]);generation+=1;pruned[actor]=generation
        elif step=='INSTALL':
            expected='NOT_READY' if actor not in pruned else 'STALE' if active or epoch!=tickets[actor] or generation!=pruned[actor] else 'OK'
            if expected=='OK':generation+=1;installed[actor]=generation
        elif step=='ACTIVATE':
            expected='NOT_READY' if actor not in installed else 'STALE' if active or epoch!=tickets[actor] or generation!=installed[actor] else 'OK'
            if expected=='OK':active=True;generation+=1
        else:raise AssertionError(step)
        if frame['outcome']!=expected:return 'INVALID',index,'outcome at '+step
        view=dict(root=dict(owner=owner,certificate=root),hot=dict(epoch=epoch,generation=generation,active=active,sequence=len(ledger),cents=cents,floor=floor,base=base,rows=ledger[floor:]),archive=objects)
        if frame['observed']!=view:return 'INVALID',index,'state at '+step
        actual=frame['observed']; restored=[]
        if floor:
            archived=actual['archive'].get(str(base['owner']))
            if archived is None or certificate(archived)!=base:return 'INVALID',index,'missing certified coverage'
            restored+=archived['receipts']
        restored+=actual['hot']['rows'];assert restored==ledger
    return 'VALID',len(frames),'independent archive/fence ledger specification'

def journal(genesis, view):
    current=genesis; remaining=dict(view['receipts'])
    while remaining:
        eligible=[(key,r) for key,r in remaining.items() if r['before']==current]
        assert len(eligible)==1, 'journal must have one exact complete chain'
        key,r=eligible[0];q=r['request']
        assert key==q['operation'] and q['expected']==r['before'] and q['next']==r['after']
        assert q['next']['guard']!=q['expected']['guard']
        current=r['after'];del remaining[key]
    assert current==view['state']

def actor(path, history):
    envelope=load(path)
    assert hashlib.sha256(envelope['payload'].encode()).hexdigest()==envelope['digest']
    a=json.loads(envelope['payload']);assert a['version']==1 and a['subject']==history['subject']
    assert a['index']==len(a['done'])
    for i,d in enumerate(a['done']):
        p=d['pending'];assert p['phase']==phases[i]
        if p['request'] is not None:
            q=p['request'];receipt=dict(request=q,before=q['expected'],after=q['next'])
            assert d['result']==dict(code='OK',receipt=receipt)
            view=history['root'] if p['store']=='phase-authority' else history['hot']
            assert view['receipts'][q['operation']]==receipt
        else:
            assert p['phase'] in ('COPY','VERIFY') and d['result'] is None
    return a

phases=['START','FREEZE','COPY','VERIFY','CERTIFY','PRUNE','INSTALL','ACTIVATE']
folder=Path(sys.argv[1]);assert folder.is_dir()
histories=list(folder.glob('*/history.json'));assert histories, 'no phase histories'
is_single=(folder/'process-pids.json').exists()
assert len(histories)==(69 if is_single else 16), len(histories)
for path in histories:
    h=load(path);assert replay(h['frames'])==(h['result']['verdict'],h['result']['checked'],h['result']['reason'])
    assert h['result']['verdict']=='VALID'
    journal(h['rootGenesis'],h['root']);journal(h['hotGenesis'],h['hot'])
    for f in path.parent.glob('actor-*.json'):actor(f,h)
    assert json.loads(h['hot']['state']['value'])==h['frames'][-1]['observed']['hot']
    assert json.loads(h['root']['state']['value'])==h['frames'][-1]['observed']['root']

mutant=load(folder/'mutant-refresh-guard/mutant.json')
assert replay(mutant['frames'])[0:2]==('INVALID',len(mutant['frames'])-1)
assert mutant['result']['verdict']=='INVALID'
q=mutant['broken'];assert q['expected']==mutant['before']['state']
assert q['next']==mutant['original']['next'] and q['expected']!=mutant['original']['expected']
assert mutant['after']['state']==q['next']
assert mutant['after']['receipts']==dict(mutant['before']['receipts'],**{q['operation']:dict(request=q,before=q['expected'],after=q['next'])})

for phase in ('CERTIFY','PRUNE','INSTALL','ACTIVATE'):
    r=load(folder/('stale-'+phase)/'rejected.json')
    assert r['error'].endswith('PHASE_CONFLICT:CONFLICT') and r['actor']['pending']['phase']==phase
for phase in ('FREEZE','CERTIFY','PRUNE','INSTALL','ACTIVATE'):
    r=load(folder/('historical-'+phase)/'historical.json')
    p=r['pending'];a=r['recovered']
    assert a['facts']==p['pending']['next']
    assert a['done'][p['index']]['pending']==p['pending']
    assert r['root']==load(folder/('historical-'+phase)/'history.json')['root']
    assert r['hot']==load(folder/('historical-'+phase)/'history.json')['hot']
blocked=load(folder/'proof-failures/blocked.json')
assert all(blocked[k] for k in ('missingActor','corruptActor','missingArchive','corruptArchive'))
saved=json.loads(json.loads(base64.b64decode(blocked['pendingBytes']))['payload'])
final=actor(folder/'proof-failures/actor-0.json',load(folder/'proof-failures/history.json'))
assert saved['pending']==final['done'][5]['pending']
race=load(folder/'start-race/initial-race.json');resolved=load(folder/'start-race/resolved-race.json')
assert max(race['invoked'])<min(race['returned'])
assert sum(r['code']=='OK' for r in resolved['results'])==1
for i,r in enumerate(resolved['results']):
    q=race['actors'][i]['pending']['request']
    if i==resolved['winner']:assert r==dict(code='OK',receipt=dict(request=q,before=q['expected'],after=q['next']))
    else:assert r==dict(code='CONFLICT',receipt=None)
race=load(folder/'edit-freeze-race/initial-race.json');resolved=load(folder/'edit-freeze-race/resolved-race.json')
assert max(race['invoked'])<min(race['returned'])
assert race['requests'][0]['expected']==race['requests'][1]['expected']
assert sum(resolved[k]['code']=='OK' for k in ('edit','freeze'))==1
for i,key in enumerate(('edit','freeze')):
    r=resolved[key];q=race['requests'][i]
    if r['code']=='OK':assert r['receipt']==dict(request=q,before=q['expected'],after=q['next'])
    else:assert r==dict(code='CONFLICT',receipt=None)
    if race['initial'][i] is not None:assert race['initial'][i]==r and race['errors'][i]==''
    else:assert 'TimeoutException' in race['errors'][i]
print(len(histories),'exact phase histories; two journal chains each; actor proofs; stale/historical recovery; actual unsafe mutation rejected')
for phase in ('PRUNE','INSTALL','ACTIVATE'):
    race=load(folder/('maintenance-race-'+phase)/'initial-race.json');resolved=load(folder/('maintenance-race-'+phase)/'resolved-race.json')
    assert max(race['invoked'])<min(race['returned']) and race['requests'][0]['expected']==race['requests'][1]['expected']
    assert sum(resolved[k]['code']=='OK' for k in ('old','newer'))==1
    for i,key in enumerate(('old','newer')):
        r=resolved[key];q=race['requests'][i]
        assert r==(dict(code='OK',receipt=dict(request=q,before=q['expected'],after=q['next'])) if r['code']=='OK' else dict(code='CONFLICT',receipt=None))
        if race['initial'][i] is not None:assert race['initial'][i]==r and race['errors'][i]==''
        else:assert 'TimeoutException' in race['errors'][i]
if is_single:
    all_pids=set()
    for phase in phases:
        for cut in ['REQUEST_BYTES_FORCED','AFTER_PENDING','AFTER_EFFECT','COMPLETION_BYTES_FORCED','AFTER_LOCAL']+(['ARCHIVE_BYTES_FORCED'] if phase=='COPY' else []):
            d=folder/('process-'+phase+'-'+cut);r=load(d/'cut.json');a=r['interrupted'];index=phases.index(phase)
            op=a['done'][index]['pending'] if cut=='AFTER_LOCAL' else r['stagedActor']['pending'] if cut=='REQUEST_BYTES_FORCED' else a['pending']
            if cut=='REQUEST_BYTES_FORCED':
                assert a['pending'] is None
                final=r['finalActor']['done'][index]['pending']
                assert final['phase']==op['phase'] and final['store']==op['store']
                if op['request']:
                    assert final['request']['operation']!=op['request']['operation']
                    h=load(d/'history.json');assert op['request']['operation'] not in h['root']['receipts'] and op['request']['operation'] not in h['hot']['receipts']
                    assert final['request']['expected']==op['request']['expected'] and final['request']['next']['value']==op['request']['next']['value']
            else:assert r['finalActor']['done'][index]['pending']==op
            assert a['index']==index+(cut=='AFTER_LOCAL')
            if cut=='COMPLETION_BYTES_FORCED':
                assert r['stagedActor']['index']==index+1 and r['stagedActor']['pending'] is None
                assert r['stagedActor']['done'][index]['pending']==op
            if cut=='ARCHIVE_BYTES_FORCED':
                unpublished=load(d/'unpublished-object.json');assert unpublished['archive']==op['object'] and unpublished['targetExists'] is False
                assert unpublished['file'].endswith('.pending')
            if cut in ('AFTER_PENDING','REQUEST_BYTES_FORCED','ARCHIVE_BYTES_FORCED'):
                assert r['afterRoot']==r['beforeRoot'] and r['afterHot']==r['beforeHot']
            elif op['request']:
                q=op['request'];view=r['afterRoot'] if op['store']=='phase-authority' else r['afterHot']
                assert view['state']==q['next'] and view['receipts'][q['operation']]==dict(request=q,before=q['expected'],after=q['next'])
            if phase=='COPY':assert (str(op['object']['owner']) in r['afterView']['archive'])==(cut not in ('AFTER_PENDING','REQUEST_BYTES_FORCED','ARCHIVE_BYTES_FORCED'))
            assert not (d/'interrupted.json').exists()
            for f in ('interrupted.json.halt','resumed.json'):
                pid=load(d/f)['pid'];assert pid not in all_pids;all_pids.add(pid)
    assert len(all_pids)==82 and all_pids==set(load(folder/'process-pids.json'))
    wire_pids=set()
    for phase in ('START','FREEZE','CERTIFY','PRUNE','INSTALL','ACTIVATE'):
        for cut in ('BEFORE_SEND','AFTER_RESPONSE'):
            d=folder/('wire-'+phase+'-'+cut);r=load(d/'cut.json');w=load(d/'wire.json')
            p=r['interrupted']['pending'];q=p['request'];idx=phases.index(phase)
            assert r['finalActor']['done'][idx]['pending']==p
            assert w['fault']==cut and w['forwarded']==(cut=='AFTER_RESPONSE')
            assert w['query'].encode() in base64.b64decode(w['requestBody'])
            h=load(d/'history.json')
            assert ('atlas-recovery-journal:'+h['subject']+':'+p['store']) in w['query']
            if cut=='BEFORE_SEND':assert r['before']==r['afterUncertainty'] and w['responseOpcode']==-1
            else:
                assert w['responseOpcode']==8
                body=base64.b64decode(w['responseBody']);assert body[:4]==b'\0\0\0\2' and b'[applied]' in body and body[-9:]==b'\0\0\0\1\0\0\0\1\1'
                assert r['afterUncertainty']['state']==q['next']
                assert r['afterUncertainty']['receipts']==dict(r['before']['receipts'],**{q['operation']:dict(request=q,before=q['expected'],after=q['next'])})
            assert 'DriverTimeoutException' in load(d/'interrupted.json')['error']
            for f in ('interrupted.json','resumed.json'):
                pid=load(d/f)['pid'];assert pid not in all_pids|wire_pids;wire_pids.add(pid)
    assert len(wire_pids)==24 and wire_pids==set(load(folder/'wire-pids.json'))
    print('41 process cuts / 82 JVMs; 12 real wire cuts / 24 JVMs; published identities retained; unpublished actor/archive bytes never trusted')
