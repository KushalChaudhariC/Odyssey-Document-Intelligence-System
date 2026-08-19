const VARIANT_BY_LABEL = {
  High: "success",
  Medium: "warning",
  Low: "danger",
};

export default function ConfidenceBadge({ label, confidence }) {
  const variant = VARIANT_BY_LABEL[label] || "secondary";
  const percentage = Math.round((confidence || 0) * 100);

  return (
    <span className={`badge rounded-pill text-bg-${variant} confidence-badge`}>
      {label} confidence · {percentage}%
    </span>
  );
}
