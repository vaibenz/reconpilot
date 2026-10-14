import type { Proposal } from './api'

type Props = {
  proposal: Proposal
  onDecide?: (bankLineId: string, decision: 'approve' | 'reject' | 'reset') => void
}

export function ProposalCard({ proposal: p, onDecide }: Props) {
  const line = p.bankLine
  const inv = p.invoice
  const pct = Math.round(p.confidence * 100)
  const failed = p.checks.filter((c) => !c.passed).length

  return (
    <article className={`card status-${p.status.toLowerCase()}`}>
      <div className="side">
        <span className="eyebrow">Bank line · {line.id}</span>
        <span className="amount">
          {line.amount} <small>{line.currency}</small>
        </span>
        <span className="who">{line.counterparty}</span>
        <span className="desc mono">{line.description}</span>
        <span className="muted">{line.date}</span>
      </div>

      <div className="middle">
        <div className="meta">
          <span className={`tag tag-${p.source.toLowerCase()}`}>{p.source === 'LLM' ? 'AI suggestion' : 'Rule match'}</span>
          <span className="confidence" title="Model confidence">
            <span className="bar">
              <span style={{ width: `${pct}%` }} />
            </span>
            {pct}%
          </span>
        </div>
        {p.rationale && <p className="rationale">“{p.rationale}”</p>}
        <ul className="checks" aria-label={failed ? `${failed} checks failed` : 'All checks passed'}>
          {p.checks.map((c) => (
            <li key={c.name} className={c.passed ? 'pass' : 'fail'}>
              <span aria-hidden>{c.passed ? '✓' : '✕'}</span>
              <strong>{c.name}</strong> {c.detail}
            </li>
          ))}
        </ul>
        {p.note && <p className="note">{p.note}</p>}
      </div>

      <div className="side">
        <span className="eyebrow">Invoice {inv ? `· ${inv.id}` : ''}</span>
        {inv ? (
          <>
            <span className="amount">
              {inv.amount} <small>{inv.currency}</small>
            </span>
            <span className="who">{inv.customer}</span>
            <span className="desc mono">{inv.number}</span>
            <span className="muted">
              Issued {inv.issueDate} · due {inv.dueDate}
            </span>
          </>
        ) : (
          <span className="muted">Invoice does not exist</span>
        )}
      </div>

      {onDecide && (
        <div className="actions">
          {p.status === 'PENDING' ? (
            <>
              <button className="approve" onClick={() => onDecide(line.id, 'approve')}>
                Approve
              </button>
              <button className="reject" onClick={() => onDecide(line.id, 'reject')}>
                Reject
              </button>
            </>
          ) : (
            <>
              <span className={`decided decided-${p.status.toLowerCase()}`}>{p.status === 'APPROVED' ? 'Approved' : 'Rejected'}</span>
              <button className="link" onClick={() => onDecide(line.id, 'reset')}>
                Undo
              </button>
            </>
          )}
        </div>
      )}
    </article>
  )
}
