import { ImageOff, Package } from "lucide-react";
import { cn } from "@/components/ui";

/** Product photo from the store's private bucket (presigned URL) or a neutral placeholder with the initial. */
export function ProductPhoto({ url, name, className, missing }: { url?: string | null; name: string; className?: string; missing?: boolean }) {
  if (url) {
    // eslint-disable-next-line @next/next/no-img-element -- presigned, short-lived URL from the store's bucket
    return <img src={url} alt="" className={cn("rounded-lg bg-muted object-cover", className)} loading="lazy" />;
  }
  const Icon = missing ? ImageOff : Package;
  return (
    <span aria-hidden className={cn("flex items-center justify-center rounded-lg bg-accent text-accent-foreground", className)}>
      {name ? <span className="text-lg font-semibold uppercase">{name.trim().charAt(0)}</span> : <Icon className="h-5 w-5" />}
    </span>
  );
}
