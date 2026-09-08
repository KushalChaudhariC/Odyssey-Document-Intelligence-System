import { useEffect, useRef, useState } from "react";
import Sidebar from "./components/Sidebar";
import ChatThread from "./components/ChatThread";
import QuestionComposer from "./components/QuestionComposer";
import SourceViewerModal from "./components/SourceViewerModal";
import { listDocuments, submitQuery } from "./api/client";
import "./App.css";

let nextMessageId = 1;

function App() {
  const [documents, setDocuments] = useState([]);
  const [documentsError, setDocumentsError] = useState(null);
  const [messages, setMessages] = useState([]);
  const [isThinking, setIsThinking] = useState(false);
  const [activeCitation, setActiveCitation] = useState(null);
  const bottomRef = useRef(null);

  useEffect(() => {
    refreshDocuments();
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isThinking]);

  async function refreshDocuments() {
    try {
      const data = await listDocuments();
      setDocuments(data);
      setDocumentsError(null);
    } catch (err) {
      setDocumentsError(err.message);
    }
  }

  function handleDocumentIngested(summary) {
    setDocuments((prev) => [summary, ...prev]);
  }

  function handleReset() {
    setDocuments([]);
    setMessages([]);
    setDocumentsError(null);
  }

  async function handleAsk(question) {
    setMessages((prev) => [...prev, { id: nextMessageId++, role: "user", text: question }]);
    setIsThinking(true);
    try {
      const response = await submitQuery(question);
      setMessages((prev) => [...prev, { id: nextMessageId++, role: "assistant", response, question }]);
    } catch (err) {
      setMessages((prev) => [...prev, { id: nextMessageId++, role: "assistant", error: err.message }]);
    } finally {
      setIsThinking(false);
    }
  }

  function handleDeepDiveResult(question, originalConfidence, deepDiveResponse) {
    setMessages((prev) => [
      ...prev,
      {
        id: nextMessageId++,
        role: "assistant",
        response: deepDiveResponse,
        question,
        isDeepDive: true,
        deepDiveImproved: deepDiveResponse.confidence > originalConfidence,
      },
    ]);
  }

  return (
    <div className="app-shell">
      <Sidebar
        documents={documents}
        onDocumentIngested={handleDocumentIngested}
        onReset={handleReset}
        refreshError={documentsError}
      />

      <main className="chat-panel">
        <ChatThread
          messages={messages}
          isThinking={isThinking}
          onViewSource={setActiveCitation}
          onDeepDiveResult={handleDeepDiveResult}
          bottomRef={bottomRef}
        />
        <QuestionComposer onSubmit={handleAsk} disabled={isThinking} />
      </main>

      <SourceViewerModal citation={activeCitation} onClose={() => setActiveCitation(null)} />
    </div>
  );
}

export default App;
