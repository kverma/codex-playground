#!/usr/bin/env python3
"""Independent bounded-profile encoder; no Java invocation or shared codec imports."""
import hashlib
import json
import uuid

GROUPS = ("ECONOMICS", "ELIGIBILITY", "ROYALTY")
def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

vectors = []
for index in range(256):
    values = [str((index * 7919) % 1000001), "NEW", str((index * 97) % 10001)]
    if index % 3 == 0:
        values[:2] = [str(index % 501), "NEW,CHURNED"]
    epoch = index % 7 + 1
    versions = [str(uuid.UUID(int=index * 8 + n + 1)) for n in range(3)]
    generation = str(uuid.UUID(int=index * 8 + 4))
    size = len("".join(g + "=" + value + ";" for g, value in zip(GROUPS, values)).encode("utf-8"))
    intent = "atlas-terms-v1|epoch=" + str(epoch) + "".join("|" + g + "=" + value for g, value in zip(GROUPS, values))
    wire = "|".join(["1", generation, str(epoch), "128", str(size)] + [item for pair in zip(versions, values) for item in pair])
    reads = {g: version for i, (g, version) in enumerate(zip(GROUPS, versions)) if index & (1 << i)}
    updates = {g: value for i, (g, value) in enumerate(zip(GROUPS, values)) if index & (1 << (i+3))}
    # Hash profiles include syntactic requests; this does not imply admission validity.
    admitted_epoch = epoch + 1 if index % 5 == 0 else None
    admitted_budget = 256 if admitted_epoch else None
    nullable = lambda value: "null" if value is None else str(value)
    request = "atlas-request-v1|" + str(epoch) + "|" + nullable(admitted_epoch) + "|" + nullable(admitted_budget)
    for group in GROUPS:
        value = updates.get(group)
        request += "|" + group + ":" + reads.get(group, "null") + ":" + ("-" if value is None else str(len(value)) + ":" + value)
    vectors.append(dict(index=index,epoch=epoch,versions=versions,generation=generation,values=values,size=size,
        intent=intent,intentHash=digest(intent),wire=wire,reads=reads,updates=updates,
        admittedEpoch=admitted_epoch,admittedBudget=admitted_budget,request=request,requestHash=digest(request)))
print(json.dumps(vectors, sort_keys=True, separators=(",", ":")))
