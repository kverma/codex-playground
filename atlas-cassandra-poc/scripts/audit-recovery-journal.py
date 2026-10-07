#!/usr/bin/env python3
"""Recheck downloaded JSON evidence without invoking the Java candidate/checker."""
import base64
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
assert root.is_dir(), root
assert any((root / name).is_dir() for name in ("recovery-journal-model", "recovery-journal-single", "recovery-journal-three", "recovery-journal-wire")), "no journal evidence"
def read(path):
    return json.loads(path.read_text())

def resolution(race, genesis):
    for side in ("left", "right"):
        attempt = race["initial" + side.title()]
        if attempt["result"] is not None:
            assert attempt["error"] is None and attempt["result"] == race[side]
        else:
            assert attempt["error"].split(":", 1)[0].rsplit(".", 1)[-1] in (
                "WriteTimeoutException", "ReadTimeoutException", "DriverTimeoutException")
    before = race["beforeResolution"]
    assert before == (race["view"] if before["receipts"] else dict(state=genesis, receipts={}))

def oracle(trace):
    current = trace["genesis"]
    receipts = {}
    for index, event in enumerate(trace["events"]):
        request = event["request"]
        operation = request["operation"]
        saved = receipts.get(operation)
        if saved is not None:
            expected = {"code": "OK", "receipt": saved} if saved["request"] == request else {"code": "KEY_REUSE", "receipt": None}
        elif request["expected"] != current:
            expected = {"code": "CONFLICT", "receipt": None}
        else:
            saved = {"request": request, "before": current, "after": request["next"]}
            receipts[operation] = saved
            current = request["next"]
            expected = {"code": "OK", "receipt": saved}
        if event["result"] != expected:
            return False, index, "exact outcome/receipt mismatch"
        if event["observed"] != {"state": current, "receipts": receipts}:
            return False, index, "atomic state/journal mismatch"
    return True, len(trace["events"]), "exact-operation specification"

model = root / "recovery-journal-model"
if model.exists():
    files = list(model.glob("*.json")); assert len(files) == 16
    boundaries = {"STATE_ONLY": 0, "RECEIPT_ONLY": 0, "REPLAY_AS_NEW": 2, "IGNORE_BINDING": 1}
    for path in files:
        trace = read(path)
        valid, checked, reason = oracle(trace)
        assert trace["result"] == dict(valid=valid, checked=checked, reason=reason)
        assert valid == (not path.name.startswith("mutant-"))
        if not valid:
            assert checked == boundaries[path.stem.removeprefix("mutant-")]
    print("16 model histories: 8 deterministic cuts, 4 exact mutants, 4 passing controls")

for name in ("recovery-journal-single", "recovery-journal-three"):
    folder = root / name
    if not folder.exists():
        continue
    trace = read(folder / "replay.json"); assert oracle(trace)[0]
    race = read(folder / "identical-race.json")
    request = race["request"]; result = race["left"]
    assert result == race["right"] == dict(code="OK", receipt=dict(request=request, before=request["expected"], after=request["next"]))
    assert max(race["invoked"]) < min(race["returned"])
    assert race["view"] == dict(state=request["next"], receipts={request["operation"]: result["receipt"]})
    resolution(race, request["expected"])
    competing = list(folder.glob("competing-*.json")); assert len(competing) == 6
    for file in competing:
        race = read(file)
        assert max(race["invoked"]) < min(race["returned"])
        winners = [side for side in ("left", "right") if race[side]["code"] == "OK"]
        assert len(winners) == 1
        winner = winners[0]; loser = "right" if winner == "left" else "left"
        request = race[winner + "Request"]
        receipt = dict(request=request, before=request["expected"], after=request["next"])
        assert race[winner] == dict(code="OK", receipt=receipt)
        assert race[loser] == dict(code="KEY_REUSE" if race["sameKey"] else "CONFLICT", receipt=None)
        assert race["view"] == dict(state=request["next"], receipts={request["operation"]: receipt})
        resolution(race, request["expected"])
    print(name, "exact historical replay and overlapping deduplication audited")

wire = root / "recovery-journal-wire"
if wire.exists():
    pids = set()
    for phase in ("START", "FREEZE", "PUBLISH", "ACTIVATE"):
        for cut in ("BEFORE_SEND", "AFTER_RESPONSE", "HALT"):
            folder = wire / (phase + "-" + cut)
            first = read(folder / "interrupted-input.json")
            retry = read(folder / "resumed-input.json")
            assert first == retry
            request = first["request"]
            observed = read(folder / "cut.json")
            assert observed["phase"] == phase and observed["cut"] == cut
            effect = dict(request=request, before=request["expected"], after=request["next"])
            expected_receipts = dict(observed["before"]["receipts"], **{request["operation"]: effect})
            after = dict(state=request["next"], receipts=expected_receipts)
            assert observed["afterUncertainty"] == (observed["before"] if cut == "BEFORE_SEND" else after)
            assert observed["afterRecovery"] == after
            interrupted = read(folder / ("interrupted-output.json.halt" if cut == "HALT" else "interrupted-output.json"))
            resumed = read(folder / "resumed-output.json")
            assert resumed["result"] == dict(code="OK", receipt=effect) and resumed["error"] is None
            for report in (interrupted, resumed):
                assert report["pid"] not in pids; pids.add(report["pid"])
            if cut == "HALT":
                assert interrupted["result"] == resumed["result"] and interrupted["error"] == "AFTER_EFFECT_BEFORE_REPLY"
                assert not (folder / "interrupted-output.json").exists()
            else:
                assert interrupted["result"] is None and "DriverTimeoutException" in interrupted["error"]
                witness = read(folder / "wire.json")
                assert witness["fault"] == cut and witness["forwarded"] == (cut == "AFTER_RESPONSE")
                assert f"atlas-recovery-journal:{first['subject']}:{first['store']}" in witness["query"]
                assert witness["query"].encode() in base64.b64decode(witness["requestBody"])
                if cut == "BEFORE_SEND":
                    assert witness["responseOpcode"] == -1
                else:
                    assert witness["responseOpcode"] == 8
                    data = base64.b64decode(witness["responseBody"])
                    assert data[:4] == b"\0\0\0\2" and b"[applied]" in data and data[-9:] == b"\0\0\0\1\0\0\0\1\1"
            for name in ("authority-history.json", "hot-history.json"):
                assert oracle(read(folder / name))[0]
            handoff = read(folder / "handoff.json")
            assert handoff["result"]["verdict"] == "VALID" and handoff["result"]["checked"] == 10
            state = handoff["frames"][-1]["observed"]
            assert state["root"]["owner"] == 2 and state["hot"]["epoch"] == 2 and state["hot"]["active"]
            assert state["root"]["checkpoint"]["image"] == state["hot"]["image"] == dict(cents=402,receipts=[dict(operation=2,before=500,after=402)])
    assert len(pids) == 24 and pids == set(read(wire / "processes.json"))
    print("12 Cassandra cuts, 24 distinct worker PIDs, 24 independent journal histories audited")
