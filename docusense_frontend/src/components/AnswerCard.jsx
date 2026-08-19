import ConfidenceBadge from "./ConfidenceBadge";

export default function AnswerCard({ response, onViewSource }) {
  const { answer, citations, confidence, confidenceLabel, servedFromCache } = response;

  return (
    <div className="answer-card">
      <div className="d-flex justify-content-between align-items-start flex-wrap gap-2 mb-2">
        <ConfidenceBadge label={confidenceLabel} confidence={confidence} />
        {servedFromCache && (
          <span className="badge rounded-pill text-bg-light border cache-badge">⚡ instant answer</span>
        )}
      </div>

      <p className="answer-text mb-0">{answer}</p>

      {citations && citations.length > 0 && (
        <div className="sources-section">
          <div className="sources-heading">Sources</div>
          <div className="d-flex flex-column gap-2">
            {citations.map((citation, index) => (
              <div className="citation-card" key={`${citation.sourceFileId}-${index}`}>
                <div className="flex-grow-1">
                  <div className="fw-semibold small">{citation.sourceFileName}</div>
                  <div className="text-secondary small mb-1">Page {citation.pageNumber}</div>
                  <div className="citation-snippet">{citation.snippet}</div>
                </div>
                <button
                  type="button"
                  className="btn btn-sm btn-outline-primary view-source-btn"
                  onClick={() => onViewSource(citation)}
                >
                  View Source
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
