export type Check = { name: string; passed: boolean; detail: string }

export type BankLine = {
  id: string
  date: string
  amount: string
  currency: string
  description: string
  counterparty: string
}

export type Invoice = {
  id: string
  number: string
  customer: string
  issueDate: string
  dueDate: string
  amount: string
  currency: string
}

export type Status = 'PENDING' | 'APPROVED' | 'REJECTED' | 'BLOCKED'

export type Proposal = {
  bankLine: BankLine
  invoice: Invoice | null
  source: 'RULES' | 'LLM'
  confidence: number
  rationale: string
  checks: Check[]
  status: Status
  note?: string | null
}

export type Summary = {
  mode: 'llm' | 'rules'
  bankLines: number
  invoices: number
  pending: number
  approved: number
  rejected: number
  blockedByChecks: number
  unmatched: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, init)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error ?? `Request failed with ${res.status}`)
  }
  return res.json() as Promise<T>
}

export const api = {
  summary: () => request<Summary>('/summary'),
  queue: () => request<Proposal[]>('/queue'),
  blocked: () => request<Proposal[]>('/blocked'),
  unmatched: () => request<BankLine[]>('/unmatched'),
  decide: (bankLineId: string, decision: 'approve' | 'reject' | 'reset') =>
    request<Summary>(`/proposals/${encodeURIComponent(bankLineId)}/${decision}`, { method: 'POST' }),
  reconcile: (bankLinesCsv: string, invoicesCsv: string) =>
    request<Summary>('/reconcile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ bankLinesCsv, invoicesCsv }),
    }),
  exportUrl: '/api/export',
}
