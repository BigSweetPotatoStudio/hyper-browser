import { loadSettings } from "./storage";
import { deleteRemoteWebApp, loadRemoteWebApps, saveRemoteWebApp, syncNow } from "./sync";

chrome.runtime.onInstalled.addListener(() => {
  loadSettings().catch(console.error);
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || typeof message.type !== "string") return false;
  handleMessage(message)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((error) => sendResponse({ ok: false, error: error instanceof Error ? error.message : String(error) }));
  return true;
});

async function handleMessage(message: { type: string; payload?: unknown }): Promise<unknown> {
  switch (message.type) {
    case "settings.get":
      return loadSettings();
    case "sync.run":
      return syncNow();
    case "webapps.list":
      return loadRemoteWebApps();
    case "webapps.save":
      return saveRemoteWebApp(message.payload as never);
    case "webapps.delete":
      return deleteRemoteWebApp(String((message.payload as { startUrl?: string })?.startUrl || ""));
    case "open.options":
      await chrome.runtime.openOptionsPage();
      return null;
    case "open.home":
      await chrome.tabs.create({ url: chrome.runtime.getURL("home.html") });
      return null;
    case "open.webapps":
      await chrome.tabs.create({ url: chrome.runtime.getURL("webapps.html") });
      return null;
    default:
      throw new Error("Unknown command.");
  }
}
