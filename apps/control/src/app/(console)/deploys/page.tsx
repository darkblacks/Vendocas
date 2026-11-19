"use client";
import { usePoll } from "@/lib/use-poll";
import { Alert, Badge, Card, Table, Td, Title, fmt, stateTone } from "@/components/ui";

interface Run { id: number; name: string; status: string; conclusion: string | null; sha: string; title: string; event: string; url: string; createdAt: string }
interface D { configured: boolean; reason?: string; repository?: string; mainCommit?: { sha: string; message: string; date: string; url: string }; runs?: Run[] }

export default function Deploys() {
  const q = usePoll<D>("/api/deploys", 60_000);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Deploys</h1>
      <Alert tone="warn">Publicação automática na Netlify está <strong>desabilitada</strong>. Os jobs de deploy só rodam após habilitar as variáveis do repositório descritas em docs/github-actions.md.</Alert>
      {q.error && <Alert tone="bad">{q.error}</Alert>}
      {q.data && !q.data.configured && <Alert tone="warn">{q.data.reason}</Alert>}
      {q.data?.mainCommit && <Card><Title>Último commit na main</Title>
        <p className="text-sm"><a className="font-mono text-primary" href={q.data.mainCommit.url} target="_blank" rel="noreferrer">{q.data.mainCommit.sha.slice(0, 10)}</a> · {q.data.mainCommit.message} · {fmt(q.data.mainCommit.date)}</p></Card>}
      {q.data?.runs && <Card><Title>Execuções do GitHub Actions (main)</Title>
        <Table head={["Workflow", "Commit", "Status", "Evento", "Quando"]}>
          {q.data.runs.map((r) => <tr key={r.id}><Td><a className="text-primary" href={r.url} target="_blank" rel="noreferrer">{r.name}</a><div className="text-xs text-muted-foreground">{r.title}</div></Td>
            <Td className="font-mono text-xs">{r.sha.slice(0, 7)}</Td><Td><Badge tone={stateTone(r.conclusion ?? r.status)}>{r.conclusion ?? r.status}</Badge></Td><Td>{r.event}</Td><Td>{fmt(r.createdAt)}</Td></tr>)}
        </Table></Card>}
    </div>
  );
}
