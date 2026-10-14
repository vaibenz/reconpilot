# ReconPilot evaluation

Dataset: 172 bank lines, 212 open invoices, 131 true matches. Generated 2026-10-15.

Model: Gemini gemini-3.5-flash-lite. LLM errors: 0.

| Pipeline | Matches made | Correct | Wrong | Precision | Recall | F1 |
|---|---|---|---|---|---|---|
| Rules only (exact reference and amount) | 60 | 60 | 0 | 1.000 | 0.458 | 0.628 |
| LLM proposals, unverified | 131 | 120 | 11 | 0.916 | 0.916 | 0.916 |
| LLM proposals + deterministic checks | 120 | 119 | 1 | 0.992 | 0.908 | 0.948 |

The verifier rejected 11 LLM suggestions: 10 were genuinely wrong, 1 were correct matches it was too strict on, and 0 lost a conflict. Every one is in `llm_rejections.jsonl`.

## Per scenario

Lines handled correctly (right invoice, or correctly left unmatched) out of total.

| Scenario | Rules | LLM raw | LLM + checks |
|---|---|---|---|
| AMBIGUOUS_TWIN | 0 / 8 | 4 / 8 | 4 / 8 |
| CLEAN | 40 / 40 | 40 / 40 | 40 / 40 |
| FEE_DEDUCTED | 0 / 20 | 20 / 20 | 20 / 20 |
| NAME_ONLY | 0 / 20 | 20 / 20 | 20 / 20 |
| PARTIAL_PAYMENT | 10 / 10 | 0 / 10 | 10 / 10 |
| REF_NUMBER_ONLY | 0 / 15 | 15 / 15 | 15 / 15 |
| REF_REFORMATTED | 20 / 20 | 20 / 20 | 20 / 20 |
| TRUNCATED_REF | 0 / 8 | 1 / 8 | 0 / 8 |
| UNRELATED | 25 / 25 | 25 / 25 | 25 / 25 |
| WRONG_CURRENCY | 6 / 6 | 6 / 6 | 6 / 6 |
