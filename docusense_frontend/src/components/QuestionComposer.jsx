import { useState } from "react";

export default function QuestionComposer({ onSubmit, disabled }) {
  const [value, setValue] = useState("");

  function handleSubmit(event) {
    event.preventDefault();
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSubmit(trimmed);
    setValue("");
  }

  return (
    <form className="composer" onSubmit={handleSubmit}>
      <input
        type="text"
        className="form-control composer-input"
        placeholder="Ask a question about your documents…"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        disabled={disabled}
      />
      <button type="submit" className="btn btn-primary composer-send" disabled={disabled || !value.trim()}>
        Send
      </button>
    </form>
  );
}
