import { describe, expect, it } from "vitest";
import { money, toMinor } from "@/lib/format";
import { defaultDefinition, linearize, newStep } from "@/features/automations/model";
import en from "../../messages/en.json";
import pt from "../../messages/pt.json";
import es from "../../messages/es.json";

function keys(o: Record<string, unknown>, p = ""): string[] {
  return Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? keys(v as Record<string, unknown>, `${p}${k}.`) : [`${p}${k}`]));
}

describe("format", () => {
  it("formats minor units per currency and locale", () => {
    expect(money(1800, "USD", "en-US")).toBe("$18.00");
    expect(money(12000, "BRL", "pt-BR")).toMatch(/R\$\s?120,00/);
    expect(money(null, "EUR")).toBe("—");
    expect(toMinor("12,5")).toBe(1250);
  });
});

describe("automation model", () => {
  it("linear mode wires conditions to the following step or end", () => {
    const steps = linearize([newStep("CONDITION"), newStep("WAIT"), newStep("SEND")]);
    expect(steps[0]!.onTrue).toBe(steps[1]!.id);
    expect(steps[0]!.onFalse).toBe("end");
    expect(steps[1]!.next).toBeUndefined();
  });
  it("default definition uses calendar months and a consent check", () => {
    const d = defaultDefinition();
    expect(d.trigger.period?.unit).toBe("CALENDAR_MONTHS");
    expect(d.steps[0]!.conditions?.[0]?.type).toBe("CONSENT");
  });
});

describe("i18n", () => {
  it("pt and es have exactly the same keys as en", () => {
    expect(keys(pt).sort()).toEqual(keys(en).sort());
    expect(keys(es).sort()).toEqual(keys(en).sort());
  });
});
