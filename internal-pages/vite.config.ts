import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react()],
  publicDir: "public",
  build: {
    outDir: "../app/src/main/assets",
    emptyOutDir: true,
    assetsDir: "internal",
    rollupOptions: {
      input: {
        home: resolve(__dirname, "home.html"),
        search: resolve(__dirname, "search.html"),
        bookmarks: resolve(__dirname, "bookmarks.html"),
        history: resolve(__dirname, "history.html")
      }
    }
  }
});
