export function PlaceholderPage({ title }: { title: string }) {
  return (
    <div>
      <h1 className="text-[20px] font-medium text-ink">{title}</h1>
      <p className="mt-2 text-[13px] text-muted">模块建设中（M5 里程碑落地）。</p>
    </div>
  );
}
