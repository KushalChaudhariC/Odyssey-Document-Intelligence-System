import { useRef, useState } from "react";
import { resetAllDocuments, uploadDocument } from "../api/client";

export default function Sidebar({ documents, onDocumentIngested, onReset, refreshError }) {
  const fileInputRef = useRef(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const [isResetting, setIsResetting] = useState(false);
  const [resetError, setResetError] = useState(null);

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

  async function handleClearAll() {
    const confirmed = window.confirm(
      "This permanently deletes every uploaded PDF, all vector store chunks, and the semantic cache. Continue?"
    );
    if (!confirmed) return;

    setIsResetting(true);
    setResetError(null);
    try {
      await resetAllDocuments();
      onReset();
    } catch (err) {
      setResetError(err.message);
    } finally {
      setIsResetting(false);
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

      <button
        type="button"
        className="btn btn-outline-danger w-100 reset-btn"
        onClick={handleClearAll}
        disabled={isResetting || documents.length === 0}
        title="Delete all uploaded PDFs, vector store chunks, and the semantic cache"
      >
        {isResetting ? (
          <>
            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
            Clearing…
          </>
        ) : (
          <>🗑 Clear all data</>
        )}
      </button>
      {resetError && <div className="sidebar-error">{resetError}</div>}
    </aside>
  );
}
