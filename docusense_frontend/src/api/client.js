import axios from "axios";

const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8081";

const client = axios.create({
  baseURL: BASE_URL,
  timeout: 60000,
});

// Every backend error comes back as { message, error, status }; surface a single readable string.
client.interceptors.response.use(
  (response) => response,
  (error) => {
    const backendMessage = error.response?.data?.message;
    const friendlyMessage =
      backendMessage ||
      (error.request
        ? "Could not reach the DocuSense server. Is the backend running?"
        : "Something went wrong while preparing the request.");
    return Promise.reject(new Error(friendlyMessage));
  }
);

export async function uploadDocument(file, onUploadProgress) {
  const formData = new FormData();
  formData.append("file", file);
  const { data } = await client.post("/api/documents/upload", formData, {
    headers: { "Content-Type": "multipart/form-data" },
    onUploadProgress,
  });
  return data;
}

export async function listDocuments() {
  const { data } = await client.get("/api/documents");
  return data;
}

export async function resetAllDocuments() {
  await client.delete("/api/documents");
}

export async function submitQuery(question) {
  const { data } = await client.post("/api/query", { question });
  return data;
}

export function documentViewUrl(sourceFileId, pageNumber) {
  return `${BASE_URL}/api/documents/${sourceFileId}#page=${pageNumber}`;
}

export default client;
