import { resolve } from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "wxt";

const iconSet = {
  16: "icons/icon-16.png",
  32: "icons/icon-32.png",
  48: "icons/icon-48.png",
  128: "icons/icon-128.png",
};

export default defineConfig({
  imports: false,
  manifest: ({ browser }) => ({
    name: "Hyper Browser Companion",
    version: "0.1.0",
    description: "Desktop launcher and WebDAV companion for Hyper Browser WebApps and bookmarks.",
    permissions: ["activeTab", "alarms", "bookmarks", "scripting", "storage"],
    host_permissions: ["http://*/*", "https://*/*"],
    icons: iconSet,
    action: {
      default_icon: iconSet,
      default_popup: "popup.html",
      default_title: "Hyper Browser Companion",
    },
    browser_specific_settings: browser === "firefox"
      ? {
          gecko: {
            id: "hyper-browser-companion@dadigua.com",
            strict_min_version: "140.0",
            data_collection_permissions: {
              required: ["bookmarksInfo", "browsingActivity", "websiteContent"],
            },
          },
          gecko_android: {
            strict_min_version: "142.0",
          },
        }
      : undefined,
  }),
  vite: () => ({
    plugins: [react()],
    resolve: {
      alias: {
        "@hyper-launcher": resolve(__dirname, "../shared/launcher/src"),
        "@hyper-launcher/": `${resolve(__dirname, "../shared/launcher/src")}/`,
        react: resolve(__dirname, "node_modules/react"),
        "react-dom": resolve(__dirname, "node_modules/react-dom"),
        "@dnd-kit/core": resolve(__dirname, "node_modules/@dnd-kit/core"),
        "@dnd-kit/sortable": resolve(__dirname, "node_modules/@dnd-kit/sortable"),
        "@dnd-kit/utilities": resolve(__dirname, "node_modules/@dnd-kit/utilities"),
      },
      dedupe: ["react", "react-dom"],
    },
  }),
});
