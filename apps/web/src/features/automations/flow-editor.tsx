"use client";
import { useMemo, useState } from "react";
import { Background, Controls, Handle, Position, ReactFlow, type Connection, type Edge, type Node, type NodeProps } from "@xyflow/react";
import { Button, Card, CardTitle, Select } from "@/components/ui";
import { STEP_TYPES, newStep, type Definition, type Step } from "./model";
import { StepEditor } from "./step-editor";

type StepNodeData = { step: Step; label: string };

function StepNode({ data, selected }: NodeProps<Node<StepNodeData>>) {
  const s = data.step;
  return (
    <div className={`min-w-44 rounded-lg border bg-card px-3 py-2 text-xs shadow-sm ${selected ? "border-primary ring-2 ring-primary/30" : "border-border"}`}>
      <Handle type="target" position={Position.Top} />
      <p className="font-semibold">{data.label}</p>
      <p className="truncate text-muted-foreground">{s.type === "SEND" ? `${s.channel} · ${s.purpose}` : s.type === "WAIT" ? `${s.duration?.amount} ${s.duration?.unit}` : s.id}</p>
      {s.type === "CONDITION" ? (
        <>
          <Handle id="true" type="source" position={Position.Bottom} style={{ left: "30%", background: "var(--success)" }} />
          <Handle id="false" type="source" position={Position.Bottom} style={{ left: "70%", background: "var(--danger)" }} />
        </>
      ) : <Handle id="next" type="source" position={Position.Bottom} />}
    </div>
  );
}

const nodeTypes = { step: StepNode };

/**
 * Advanced mode: the same definition rendered as a graph. Connecting handles sets next / onTrue / onFalse.
 * Positions are stored in step.ui and ignored by the engine.
 */
export function FlowEditor({ def, onChange }: { def: Definition; onChange: (d: Definition) => void }) {
  const [selected, setSelected] = useState<string | null>(null);
  const [addType, setAddType] = useState("SEND");

  const nodes: Node<StepNodeData>[] = useMemo(() => [
    { id: "__trigger", type: "input", position: { x: 240, y: 0 }, data: { label: `WHEN ${def.trigger.type}` } as never, deletable: false },
    ...def.steps.map((s, i) => ({ id: s.id, type: "step", position: s.ui ?? { x: 240, y: 110 * (i + 1) }, data: { step: s, label: s.type.replaceAll("_", " ") } })),
  ], [def]);

  const edges: Edge[] = useMemo(() => {
    const out: Edge[] = [];
    if (def.steps[0]) out.push({ id: "e-trigger", source: "__trigger", target: def.steps[0].id });
    def.steps.forEach((s, i) => {
      if (s.type === "CONDITION") {
        if (s.onTrue && s.onTrue !== "end") out.push({ id: `${s.id}-t`, source: s.id, sourceHandle: "true", target: s.onTrue, label: "yes", style: { stroke: "var(--success)" } });
        if (s.onFalse && s.onFalse !== "end") out.push({ id: `${s.id}-f`, source: s.id, sourceHandle: "false", target: s.onFalse, label: "no", style: { stroke: "var(--danger)" } });
      } else if (s.type !== "END") {
        const next = s.next ?? def.steps[i + 1]?.id;
        if (next && next !== "end") out.push({ id: `${s.id}-n`, source: s.id, sourceHandle: "next", target: next, animated: !s.next });
      }
    });
    return out;
  }, [def]);

  const update = (id: string, patch: Partial<Step>) => onChange({ ...def, steps: def.steps.map((s) => (s.id === id ? { ...s, ...patch } : s)) });

  const onConnect = (c: Connection) => {
    if (!c.source || !c.target || c.source === "__trigger") return;
    if (c.sourceHandle === "true") update(c.source, { onTrue: c.target });
    else if (c.sourceHandle === "false") update(c.source, { onFalse: c.target });
    else update(c.source, { next: c.target });
  };

  const sel = def.steps.find((s) => s.id === selected);
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <div className="h-[560px] rounded-lg border border-border bg-card lg:col-span-2">
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView onConnect={onConnect}
          onNodeClick={(_, n) => setSelected(n.id === "__trigger" ? null : n.id)}
          onNodeDragStop={(_, n) => n.id !== "__trigger" && update(n.id, { ui: { x: Math.round(n.position.x), y: Math.round(n.position.y) } })}
          onEdgesDelete={(es) => es.forEach((e) => {
            if (e.sourceHandle === "true") update(e.source, { onTrue: "end" });
            else if (e.sourceHandle === "false") update(e.source, { onFalse: "end" });
            else update(e.source, { next: "end" });
          })}>
          <Background />
          <Controls />
        </ReactFlow>
      </div>
      <Card>
        <div className="flex gap-2">
          <Select aria-label="step type" value={addType} onChange={(e) => setAddType(e.target.value)}>{STEP_TYPES.map((x) => <option key={x}>{x}</option>)}</Select>
          <Button onClick={() => { const s = newStep(addType); s.ui = { x: 520, y: 120 + 40 * def.steps.length }; onChange({ ...def, steps: [...def.steps, s] }); setSelected(s.id); }}>+</Button>
        </div>
        <p className="mt-2 text-xs text-muted-foreground">Drag from a handle to another step to connect. Condition: green = yes, red = no. Select an edge and press Delete to remove it.</p>
        {sel && (
          <div className="mt-4 space-y-3">
            <CardTitle>{sel.type} · <span className="font-mono text-xs">{sel.id}</span></CardTitle>
            <StepEditor step={sel} onChange={(s) => onChange({ ...def, steps: def.steps.map((x) => (x.id === s.id ? s : x)) })} />
            <Button variant="danger" size="sm" onClick={() => {
              onChange({ ...def, steps: def.steps.filter((x) => x.id !== sel.id).map((x) => ({
                ...x, next: x.next === sel.id ? undefined : x.next, onTrue: x.onTrue === sel.id ? "end" : x.onTrue, onFalse: x.onFalse === sel.id ? "end" : x.onFalse })) });
              setSelected(null);
            }}>Remove step</Button>
          </div>
        )}
      </Card>
    </div>
  );
}
