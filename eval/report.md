# ReconPilot evaluation

Dataset: 172 bank lines, 212 open invoices, 131 true matches. Generated 2026-10-14.

No ANTHROPIC_API_KEY set and no cached replies, so only the rules baseline ran.

| Pipeline | Matches made | Correct | Wrong | Precision | Recall | F1 |
|---|---|---|---|---|---|---|
| Rules only (exact reference and amount) | 60 | 60 | 0 | 1.000 | 0.458 | 0.628 |

## Per scenario

Lines handled correctly (right invoice, or correctly left unmatched) out of total.

| Scenario | Rules |
|---|---|
| AMBIGUOUS_TWIN | 0 / 8 |
| CLEAN | 40 / 40 |
| FEE_DEDUCTED | 0 / 20 |
| NAME_ONLY | 0 / 20 |
| PARTIAL_PAYMENT | 10 / 10 |
| REF_NUMBER_ONLY | 0 / 15 |
| REF_REFORMATTED | 20 / 20 |
| TRUNCATED_REF | 0 / 8 |
| UNRELATED | 25 / 25 |
| WRONG_CURRENCY | 6 / 6 |
