import { useRef, useState } from "react";
import { uploadDocument } from "../api/client";

export default function Sidebar({ documents, onDocumentIngested, refreshError }) {
  const fileInputRef = useRef(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);

  async function handleFileSelected(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;

    setIsUploading(true);
    setUploadError(null);
    try {
      const summary = await uploadDocument(file);
      onDocumentIngested(summary);
    } catch (err) {
      setUploadError(err.message);
    } finally {
      setIsUploading(false);
    }
  }

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <span className="brand-mark">📄</span>
        <span className="brand-name">DocuSense</span>
      </div>

      <button
        type="button"
        className="btn btn-primary w-100 upload-btn"
        onClick={() => fileInputRef.current?.click()}
        disabled={isUploading}
      >
        {isUploading ? (
          <>
            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
            Uploading…
          </>
        ) : (
          <>+ Upload PDF</>
        )}
      </button>
      <input
        ref={fileInputRef}
        type="file"
        accept="application/pdf"
        className="d-none"
        onChange={handleFileSelected}
      />

      {uploadError && <div className="sidebar-error">{uploadError}</div>}
      {refreshError && <div className="sidebar-error">{refreshError}</div>}

      <div className="sidebar-section-title">Document library</div>
      <div className="document-list">
        {documents.length === 0 ? (
          <p className="text-secondary small px-3">No documents yet. Upload a PDF to get started.</p>
        ) : (
          documents.map((doc) => (
            <div className="document-item" key={doc.sourceFileId}>
              <div className="document-icon">📄</div>
              <div className="document-info">
                <div className="document-name" title={doc.fileName}>
                  {doc.fileName}
                </div>
                <div className="document-meta">{doc.chunkCount} chunk{doc.chunkCount === 1 ? "" : "s"}</div>
              </div>
            </div>
          ))
        )}
      </div>
    </aside>
  );
}
