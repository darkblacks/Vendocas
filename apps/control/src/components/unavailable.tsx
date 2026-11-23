"use client";
import { useEffect } from "react";
import { Button, Card } from "./ui";

/** Shown when the session could not be checked (database slow/unreachable). The user stays signed in. */
export function TemporarilyUnavailable() {
  useEffect(() => {
    const id = setTimeout(() => window.location.reload(), 8000);
    return () => clearTimeout(id);
  }, []);
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm space-y-3">
        <h1 className="text-lg font-semibold">Serviço temporariamente indisponível</h1>
        <p className="text-sm text-muted-foreground">Não foi possível falar com o banco de dados agora (ele pode estar acordando). Você continua conectado; tentaremos de novo em instantes.</p>
        <Button className="w-full" onClick={() => window.location.reload()}>Tentar agora</Button>
      </Card>
    </main>
  );
}
