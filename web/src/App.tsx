import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type BankLine, type Proposal, type Summary } from './api'
import { ProposalCard } from './ProposalCard'
import { UploadPanel } from './UploadPanel'

type Tab = 'queue' | 'blocked' | 'unmatched'
type Filter = 'PENDING' | 'ALL'

export default function App() {
  const [summary, setSummary] = useState<Summary | null>(null)
  const [queue, setQueue] = useState<Proposal[]>([])
  const [blocked, setBlocked] = useState<Proposal[]>([])
  const [unmatched, setUnmatched] = useState<BankLine[]>([])
  const [tab, setTab] = useState<Tab>('queue')
  const [filter, setFilter] = useState<Filter>('PENDING')
  const [error, setError] = useState<string | null>(null)
  const [showUpload, setShowUpload] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [s, q, b, u] = await Promise.all([api.summary(), api.queue(), api.blocked(), api.unmatched()])
      setSummary(s)
      setQueue(q)
      setBlocked(b)
      setUnmatched(u)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const decide = async (id: string, decision: 'approve' | 'reject' | 'reset') => {
    try {
      const s = await api.decide(id, decision)
      setSummary(s)
      const status = decision === 'approve' ? 'APPROVED' : decision === 'reject' ? 'REJECTED' : 'PENDING'
      setQueue((q) => q.map((p) => (p.bankLine.id === id ? { ...p, status } : p)))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const approveAllExact = async () => {
    const exact = queue.filter(
      (p) => p.status === 'PENDING' && p.checks.every((c) => c.passed) && p.checks.some((c) => c.name === 'amount' && c.detail === 'exact') && p.checks.some((c) => c.name === 'reference' && c.detail.startsWith('description cites')),
    )
    for (const p of exact) await decide(p.bankLine.id, 'approve')
  }

  const visibleQueue = useMemo(() => (filter === 'PENDING' ? queue.filter((p) => p.status === 'PENDING') : queue), [queue, filter])

  return (
    <div className="shell">
      <header className="top">
        <div className="brand">
          <span className="logo" aria-hidden>
            ✓
          </span>
          <div>
            <h1>ReconPilot</h1>
            <p className="sub">AI proposes, checks verify, you approve.</p>
          </div>
        </div>
        <div className="top-actions">
          {summary && <span className={`mode mode-${summary.mode}`}>{summary.mode === 'llm' ? 'LLM matching' : 'Rules only'}</span>}
          <button className="ghost" onClick={() => setShowUpload((v) => !v)}>
            {showUpload ? 'Close' : 'Upload CSVs'}
          </button>
          <a className="button" href={api.exportUrl} download="approved_matches.csv">
            Export approved
          </a>
        </div>
      </header>

      {showUpload && (
        <UploadPanel
          onDone={async () => {
            setShowUpload(false)
            await refresh()
          }}
          onError={setError}
        />
      )}

      {error && (
        <div className="error" role="alert">
          {error}
          <button className="link" onClick={() => setError(null)}>
            Dismiss
          </button>
        </div>
      )}

      {summary && (
        <section className="stats" aria-label="Summary">
          <Stat label="Bank lines" value={summary.bankLines} />
          <Stat label="Pending review" value={summary.pending} tone="accent" />
          <Stat label="Approved" value={summary.approved} tone="good" />
          <Stat label="Rejected by you" value={summary.rejected} />
          <Stat label="Blocked by checks" value={summary.blockedByChecks} tone="warn" />
          <Stat label="Unmatched" value={summary.unmatched} />
        </section>
      )}

      <nav className="tabs" role="tablist">
        <TabButton active={tab === 'queue'} onClick={() => setTab('queue')} label="Review queue" count={queue.length} />
        <TabButton active={tab === 'blocked'} onClick={() => setTab('blocked')} label="Blocked by checks" count={blocked.length} />
        <TabButton active={tab === 'unmatched'} onClick={() => setTab('unmatched')} label="Unmatched" count={unmatched.length} />
      </nav>

      {tab === 'queue' && (
        <section>
          <div className="toolbar">
            <div className="segmented" role="group" aria-label="Filter">
              <button className={filter === 'PENDING' ? 'on' : ''} onClick={() => setFilter('PENDING')}>
                Pending
              </button>
              <button className={filter === 'ALL' ? 'on' : ''} onClick={() => setFilter('ALL')}>
                All
              </button>
            </div>
            <button className="ghost" onClick={approveAllExact} title="Approve proposals with an exact amount and a cited reference">
              Approve all exact matches
            </button>
          </div>
          {visibleQueue.length === 0 ? (
            <Empty text={filter === 'PENDING' ? 'Nothing left to review.' : 'No proposals yet. Upload a bank statement and invoices.'} />
          ) : (
            <div className="list">
              {visibleQueue.map((p) => (
                <ProposalCard key={p.bankLine.id} proposal={p} onDecide={decide} />
              ))}
            </div>
          )}
        </section>
      )}

      {tab === 'blocked' && (
        <section>
          <p className="hint">Suggestions that failed a deterministic check or lost a conflict. They never reach the queue, but they are kept here so you can audit what the model got wrong.</p>
          {blocked.length === 0 ? (
            <Empty text="No suggestions were blocked." />
          ) : (
            <div className="list">
              {blocked.map((p, i) => (
                <ProposalCard key={`${p.bankLine.id}-${p.invoice?.id ?? 'none'}-${i}`} proposal={p} />
              ))}
            </div>
          )}
        </section>
      )}

      {tab === 'unmatched' && (
        <section>
          {unmatched.length === 0 ? (
            <Empty text="Every bank line has a proposal." />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Line</th>
                    <th>Date</th>
                    <th className="num">Amount</th>
                    <th>Payer</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody>
                  {unmatched.map((l) => (
                    <tr key={l.id}>
                      <td className="mono">{l.id}</td>
                      <td>{l.date}</td>
                      <td className="num mono">
                        {l.amount} {l.currency}
                      </td>
                      <td>{l.counterparty}</td>
                      <td className="muted">{l.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'accent' | 'good' | 'warn' }) {
  return (
    <div className={`stat ${tone ?? ''}`}>
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

function TabButton({ active, onClick, label, count }: { active: boolean; onClick: () => void; label: string; count: number }) {
  return (
    <button role="tab" aria-selected={active} className={active ? 'tab active' : 'tab'} onClick={onClick}>
      {label} <span className="count">{count}</span>
    </button>
  )
}

function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>
}
