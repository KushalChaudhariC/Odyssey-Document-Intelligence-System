import { documentFileUrl } from "../api/client";
import PdfPageViewer from "./PdfPageViewer";

export default function SourceViewerModal({ citation, onClose }) {
  if (!citation) return null;

  return (
    <div className="source-modal-backdrop" onClick={onClose}>
      <div className="source-modal" onClick={(e) => e.stopPropagation()}>
        <div className="d-flex justify-content-between align-items-center source-modal-header">
          <div>
            <div className="fw-semibold">{citation.sourceFileName}</div>
            <div className="text-secondary small">Page {citation.pageNumber}</div>
          </div>
          <button type="button" className="btn-close" aria-label="Close" onClick={onClose}></button>
        </div>
        <div className="source-modal-body">
          <PdfPageViewer
            fileUrl={documentFileUrl(citation.sourceFileId)}
            pageNumber={citation.pageNumber}
            highlight={citation.highlight}
          />
        </div>
      </div>
    </div>
  );
}
