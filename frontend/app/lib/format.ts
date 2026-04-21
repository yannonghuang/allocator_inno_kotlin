const qtyFormatter = new Intl.NumberFormat(undefined, {
  maximumFractionDigits: 0,
});

export function qtyFmt(x: number | null | undefined): string {
  if (x == null || !Number.isFinite(x)) return "-";
  return qtyFormatter.format(Math.round(x));
}
