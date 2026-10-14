import { useState } from 'react'
import { api } from './api'

type Props = { onDone: () => void; onError: (message: string) => void }

export function UploadPanel({ onDone, onError }: Props) {
  const [bank, setBank] = useState<File | null>(null)
  const [invoices, setInvoices] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!bank || !invoices) return
    setBusy(true)
    try {
      await api.reconcile(await bank.text(), await invoices.text())
      onDone()
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="upload" onSubmit={submit}>
      <label>
        <span>Bank statement CSV</span>
        <input type="file" accept=".csv,text/csv" onChange={(e) => setBank(e.target.files?.[0] ?? null)} />
        <small className="muted">id, date, amount, currency, description, counterparty</small>
      </label>
      <label>
        <span>Open invoices CSV</span>
        <input type="file" accept=".csv,text/csv" onChange={(e) => setInvoices(e.target.files?.[0] ?? null)} />
        <small className="muted">id, number, customer, issue_date, due_date, amount, currency</small>
      </label>
      <button type="submit" className="button" disabled={!bank || !invoices || busy}>
        {busy ? 'Reconciling…' : 'Run reconciliation'}
      </button>
    </form>
  )
}
