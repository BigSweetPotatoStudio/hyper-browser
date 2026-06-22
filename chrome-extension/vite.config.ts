import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  publicDir: "public",
  resolve: {
    alias: {
      "@hyper-launcher": resolve(__dirname, "../shared/launcher/src"),
      react: resolve(__dirname, "node_modules/react"),
      "react-dom": resolve(__dirname, "node_modules/react-dom"),
      "@dnd-kit/core": resolve(__dirname, "node_modules/@dnd-kit/core"),
      "@dnd-kit/sortable": resolve(__dirname, "node_modules/@dnd-kit/sortable"),
      "@dnd-kit/utilities": resolve(__dirname, "node_modules/@dnd-kit/utilities")
    },
    dedupe: ["react", "react-dom"]
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        background: resolve(__dirname, "src/background.ts"),
        home: resolve(__dirname, "home.html"),
        popup: resolve(__dirname, "popup.html"),
        options: resolve(__dirname, "options.html"),
        webapps: resolve(__dirname, "webapps.html")
      },
      output: {
        entryFileNames: "assets/[name].js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: "assets/[name][extname]"
      }
    }
  }
});
