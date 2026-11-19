"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { Button, EmptyState, PageHeader, Spinner, StatusBadge, Table, Td } from "@/components/ui";

interface Summary { id: string; name: string; status: string; triggerType?: string; activeVersion?: number; running: number; completed: number; blocked: number; updatedAt: string }

export default function AutomationsPage() {
  const t = useTranslations("automations");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["automations"], queryFn: () => api<Summary[]>("/automations") });
  return (
    <div>
      <PageHeader title={t("title")} description={t("subtitle")} actions={<Button asChild><Link href="/app/automations/new">{t("new")}</Link></Button>} />
      {q.isLoading && <Spinner />}
      {q.data?.length === 0 && <EmptyState title={t("new")} description={t("subtitle")} action={<Button asChild><Link href="/app/automations/new">{t("new")}</Link></Button>} />}
      {!!q.data?.length && (
        <Table head={["Name", "Trigger", "Status", "Version", t("running"), t("completed"), t("skipped"), "Updated"]}>
          {q.data.map((a) => (
            <tr key={a.id}><Td><Link href={`/app/automations/${a.id}`} className="font-medium text-primary">{a.name}</Link></Td>
              <Td className="text-xs">{a.triggerType ? t(`triggers.${a.triggerType}`) : "—"}</Td><Td><StatusBadge status={a.status} /></Td><Td>{a.activeVersion ?? "—"}</Td>
              <Td>{a.running}</Td><Td>{a.completed}</Td><Td>{a.blocked}</Td><Td className="text-xs">{dateTime(a.updatedAt, locale)}</Td></tr>
          ))}
        </Table>
      )}
    </div>
  );
}
