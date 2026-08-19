import AnswerCard from "./AnswerCard";

export default function ChatThread({ messages, isThinking, onViewSource, bottomRef }) {
  if (messages.length === 0 && !isThinking) {
    return (
      <div className="chat-empty-state">
        <div className="chat-empty-icon">💬</div>
        <h2 className="h5 mb-2">Ask DocuSense anything</h2>
        <p className="text-secondary mb-0">
          Upload a document from the sidebar, then ask a question in plain English.
          Every answer comes with its confidence level and the exact source page.
        </p>
      </div>
    );
  }

  return (
    <div className="chat-thread">
      {messages.map((message) =>
        message.role === "user" ? (
          <div className="d-flex justify-content-end" key={message.id}>
            <div className="user-bubble">{message.text}</div>
          </div>
        ) : (
          <div className="d-flex justify-content-start" key={message.id}>
            {message.error ? (
              <div className="error-card">{message.error}</div>
            ) : (
              <AnswerCard response={message.response} onViewSource={onViewSource} />
            )}
          </div>
        )
      )}

      {isThinking && (
        <div className="d-flex justify-content-start">
          <div className="answer-card thinking-card">
            <span className="spinner-border spinner-border-sm text-secondary" role="status" aria-hidden="true"></span>
            <span className="ms-2 text-secondary">Searching documents and drafting an answer…</span>
          </div>
        </div>
      )}
      <div ref={bottomRef} />
    </div>
  );
}
