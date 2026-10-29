import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "@fontsource-variable/vazirmatn";
import "./styles.css";

// The app is RTL-first (fa). Latin fragments inside Persian sentences are isolated by the
// shared format helpers, so the DOM direction stays RTL everywhere.
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
