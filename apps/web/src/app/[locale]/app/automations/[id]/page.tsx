"use client";
import { use } from "react";
import { AutomationEditor } from "@/features/automations/editor";

export default function EditAutomation({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <AutomationEditor id={id} />;
}
