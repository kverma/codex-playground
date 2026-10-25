# MCP Tool Response Normalization, Provenance & Recovery

## Why this exists

MCP tools can return incomplete JSON, incorrect types, text, markdown code fences,
or a failed optional service. Without normalization, a rich OpenUI view may
discard useful information or mistakenly display "Free", "No tracking", or
"0 reviews" for absent data.

The runtime now has a typed normalization boundary **before** the OpenUI binder.

## Normalization contracts

See `corpus/normalization.json`. A capability declares normalized field paths,
types, aliases, optional/essential behavior, and explicitly labeled unavailable
placeholders. Example:

```json
{
  "source": "mock-pricing",
  "fields": {
    "offer.price": {
      "type": "string",
      "aliases": ["price", "cost"],
      "missing": "unavailable",
      "placeholder": "Unavailable",
      "recoverable": true
    }
  }
}
```

A placeholder **must not be interpreted as a provider-supplied fact**. The
normalizer adds an explicit `quality` envelope alongside normal props:

```json
{
  "offer": {"price": "Unavailable", "currency": "USD"},
  "status": "partial",
  "quality": {
    "status": "partial",
    "source": "mock-pricing",
    "fields": {
      "offer.price": {
        "status": "missing",
        "source": "mock-pricing",
        "reason": "Field not supplied"
      }
    }
  }
}
```

Field states: `present`, `coerced`, `extracted`, `missing`, `invalid`,
`recovered`, or `unavailable`. Do not equate present `items: []` with
an absent `items` field. For a completely failed optional MCP service,
the registered fallback is normalized with status `unavailable`, not `ok`.

## Parsing and model assistance

`src/tool-normalizer.ts` accepts:
- valid structured object or MCP `structuredContent`
- JSON strings and fenced JSON blocks
- simple exact `key: value` lines for declared field names/aliases

It does **not** infer claims from free-form prose or execute code found in
MCP responses. Unambiguous numeric-string conversion must be explicitly
enabled in the field rule. An extractor callback is available for use by an
approved model. It can only fill registered fields; every proposed string
must appear verbatim in the cited source excerpt, and that excerpt must
appear verbatim in the original response. Invalid proposals fail verification.

This is **evidence presence checking**, not full semantic verification:
a malicious or false underlying MCP response is not made trustworthy just
because it contains a matching quote. The optional extractor callback is
not automatically connected to an external LLM in the demo server.

## Recovery policy

The rich detail recipe includes:

```json
{
  "recovery": {
    "capability": "pricing.recover",
    "args": {
      "appId": {"$param": "id"},
      "storefront": {"$param": "storefront"}
    },
    "fields": ["offer.price"]
  }
}
```

The executor calls the recovery capability **once** only when a recoverable
field is absent/invalid. It passes only the missing allowlisted paths.
`mergeRecoveredFields` merges only the requested fields into the original
response, leaving existing valid values and other MCP results unchanged.
Recovery also has independent permission and output schema checks.

If recovery fails, the original partial response survives and the
`failures[nodeId+":recovery"]` warning is exposed. The UI continues to show
missing values as unavailable. Required original MCP failures still abort
the view instead of fabricating details.

## Live browser validation

GitHub Actions runs the API with `MOCK_PARTIAL_PRIVACY=1` and
`MOCK_PARTIAL_PRICING=1`. The mock browser conversation proves:
- privacy collection remains visible when tracking is absent
- the tracking field reads "Unavailable" and carries a `missing` status
- an initially absent price is recovered through the dedicated price-field
  MCP call and displays `recovered`
- other sources (reviews, compatibility and app details) remain intact

The CI suite also tests entirely unstructured text, wrong types, hallucinated
extraction claims, schema-required fields, and invalid recovery paths.

## Limits

Field types in the demo currently validate primitives and object/array
top-level shapes. They do not yet implement arbitrary deep JSON Schema
constraints, cache-backed stale values, adaptive retries, or automatic
selection of a model extractor. Production connectors should add strict
provider-level schemas, signed provenance where appropriate, tracing, and
per-field freshness/TTL. Tool text is untrusted input, never instructions.
