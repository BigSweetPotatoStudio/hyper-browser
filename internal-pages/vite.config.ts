import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react()],
  publicDir: "public",
  resolve: {
    alias: {
      "@hyper-launcher": resolve(__dirname, "../shared/launcher/src"),
      "@hyper-sync": resolve(__dirname, "../shared/sync/src"),
      react: resolve(__dirname, "node_modules/react"),
      "react-dom": resolve(__dirname, "node_modules/react-dom"),
      "@dnd-kit/core": resolve(__dirname, "node_modules/@dnd-kit/core"),
      "@dnd-kit/sortable": resolve(__dirname, "node_modules/@dnd-kit/sortable"),
      "@dnd-kit/utilities": resolve(__dirname, "node_modules/@dnd-kit/utilities")
    },
    dedupe: ["react", "react-dom"]
  },
  build: {
    outDir: "../app/src/main/assets",
    emptyOutDir: true,
    assetsDir: "internal",
    rollupOptions: {
      input: {
        background: resolve(__dirname, "background.html"),
        home: resolve(__dirname, "home.html"),
        settings: resolve(__dirname, "settings.html"),
        bookmarks: resolve(__dirname, "bookmarks.html"),
        history: resolve(__dirname, "history.html")
      }
    }
  }
});
