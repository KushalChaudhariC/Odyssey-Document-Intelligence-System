import { useEffect, useRef, useState } from "react";
import * as pdfjsLib from "pdfjs-dist";
import pdfjsWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl;

/**
 * Renders one page of a PDF onto a canvas and, if a highlight box is given, draws a
 * highlight rectangle over it. The box is expressed as page-relative fractions (0-1) so it
 * scales correctly regardless of how large the canvas is rendered.
 */
export default function PdfPageViewer({ fileUrl, pageNumber, highlight }) {
  const containerRef = useRef(null);
  const canvasRef = useRef(null);
  const [renderedSize, setRenderedSize] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    let renderTask = null;

    async function render() {
      setError(null);
      setRenderedSize(null);
      try {
        const pdf = await pdfjsLib.getDocument(fileUrl).promise;
        if (cancelled) return;
        const page = await pdf.getPage(pageNumber);
        if (cancelled) return;

        const containerWidth = containerRef.current?.clientWidth || 800;
        const baseViewport = page.getViewport({ scale: 1 });
        const scale = containerWidth / baseViewport.width;
        const viewport = page.getViewport({ scale });

        const canvas = canvasRef.current;
        const context = canvas.getContext("2d");
        canvas.width = viewport.width;
        canvas.height = viewport.height;

        renderTask = page.render({ canvasContext: context, viewport });
        await renderTask.promise;
        if (cancelled) return;
        setRenderedSize({ width: viewport.width, height: viewport.height });
      } catch (err) {
        if (cancelled || err?.name === "RenderingCancelledException") {
          // no-op: either unmounted, or superseded by a newer render request
        } else if (err?.name === "MissingPDFException") {
          setError("This source document is no longer available. It may have been removed or replaced with a newer version.");
        } else {
          setError("Could not render this page of the PDF.");
        }
      }
    }

    render();
    return () => {
      cancelled = true;
      renderTask?.cancel();
    };
  }, [fileUrl, pageNumber]);

  return (
    <div ref={containerRef} className="pdf-page-viewer">
      <canvas ref={canvasRef} />
      {highlight && renderedSize && (
        <div
          className="pdf-highlight-box"
          style={{
            left: highlight.x * renderedSize.width,
            top: highlight.y * renderedSize.height,
            width: highlight.width * renderedSize.width,
            height: highlight.height * renderedSize.height,
          }}
        />
      )}
      {error && <div className="pdf-page-error">{error}</div>}
    </div>
  );
}
