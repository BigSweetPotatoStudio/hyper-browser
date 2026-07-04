import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  DEFAULT_LAUNCHER_DOCK_ENTRY_IDS,
  LauncherPage,
  createLauncherLabels,
  createLauncherSystemEntries,
  type LauncherApp,
  type LauncherPlatform,
  type LauncherSystemEntryTheme,
} from "@hyper-launcher";
import { createLauncherWebDavSyncLabels, useLauncherWebDavSync, type LauncherWebDavSyncEvent, type LauncherWebDavSyncOptions } from "@hyper-sync/launcher-webdav-sync";
import { formatSyncResult, isSyncResultLike } from "@hyper-sync/sync-result";
import type { SyncSettingsDialogValues } from "@hyper-sync/settings-dialog";
import "../hyper-browser";
import type { BrowserSettings, WebAppItem, WebDavSyncResult } from "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";
import { createLauncherLayoutStorage, waitForLauncherLayoutSaves } from "../launcher-layout-storage";
import { isPlainObject, sendBackgroundCommand } from "../background-command";

const AUTO_SYNC_DEBOUNCE_MS = 1800;
const ANDROID_SYSTEM_ENTRY_THEME: LauncherSystemEntryTheme = {
  bookmarks: { color: "#0d7f66" },
  history: { color: "#f4a900" },
  extensions: { color: "#d73547" },
};

function HomePage() {
  const storage = useMemo(() => createLauncherLayoutStorage(), []);
  const translate = useCallback((key: string, values?: Record<string, unknown>) =>
    t(key as Parameters<typeof t>[0], (values || {}) as Record<string, string | number>),
  []);
  const webDavLabels = useMemo(() => createLauncherWebDavSyncLabels(translate), [translate]);
  const launcherLabels = useMemo(() => createLauncherLabels(translate), [translate]);
  const pendingDeleteResolver = useRef<((confirmed: boolean) => void) | null>(null);
  const [pendingDeleteApp, setPendingDeleteApp] = useState<LauncherApp | null>(null);

  useEffect(() => () => {
    pendingDeleteResolver.current?.(false);
    pendingDeleteResolver.current = null;
  }, []);

  const requestDeleteConfirmation = useCallback((app: LauncherApp) => new Promise<boolean>((resolve) => {
    pendingDeleteResolver.current?.(false);
    pendingDeleteResolver.current = resolve;
    setPendingDeleteApp(app);
  }), []);

  const finishDeleteConfirmation = useCallback((confirmed: boolean) => {
    pendingDeleteResolver.current?.(confirmed);
    pendingDeleteResolver.current = null;
    setPendingDeleteApp(null);
  }, []);

  const saveSyncSettings = useCallback(async (values: SyncSettingsDialogValues): Promise<BrowserSettings> => {
    if (!isHttpUrl(values.webDavUrl.trim())) {
      throw new Error(t("settings.webDavUrlRequired"));
    }
    return window.hyperBrowser.updateWebDavSyncSettings({
      webDavSyncEnabled: values.webDavUrl.trim().length > 0,
      webDavSyncUrl: values.webDavUrl.trim(),
      webDavSyncUsername: values.username.trim(),
      webDavSyncPassword: values.password,
      webDavSyncDeviceName: "Android",
    });
  }, []);

  const subscribeSyncEvents = useCallback((listener: (event: LauncherWebDavSyncEvent<WebDavSyncResult>) => void) => {
    const onMessage = (message: unknown) => {
      if (!isPlainObject(message)) return;
      if (message.type !== "remote.synced" && message.type !== "launcher.changed") return;
      listener({
        type: message.type,
        syncResult: isSyncResultLike(message.syncResult) ? message.syncResult as WebDavSyncResult : null,
      });
    };
    browser?.runtime?.onMessage?.addListener(onMessage);
    return () => browser?.runtime?.onMessage?.removeListener(onMessage);
  }, []);

  const webDavOptions = useMemo<LauncherWebDavSyncOptions<BrowserSettings, WebDavSyncResult>>(() => ({
    labels: webDavLabels,
    loadSettings: () => window.hyperBrowser.requestSettingsData(),
    saveSettings: saveSyncSettings,
    isConfigured: isWebDavConfigured,
    settingsToDialogValues,
    normalizeDialogValues,
    runSync: (options) => sendBackgroundCommand<WebDavSyncResult>(
      "sync.run",
      options?.mode ? { mode: options.mode } : undefined,
    ),
    scheduleSyncSoon: () => sendBackgroundCommand("sync.soon"),
    summarizeResult: webDavSyncSummary,
    resultSettings: (result) => result.settings,
    subscribeSyncEvents,
    beforeManualSync: waitForLauncherLayoutSaves,
    beforeScheduleSync: waitForLauncherLayoutSaves,
    autoSyncDebounceMs: AUTO_SYNC_DEBOUNCE_MS,
    showFolderTitle: false,
  }), [saveSyncSettings, subscribeSyncEvents, webDavLabels]);
  const webDavSync = useLauncherWebDavSync(webDavOptions);

  const systemEntries = useMemo(() => createLauncherSystemEntries(translate, ANDROID_SYSTEM_ENTRY_THEME), [translate]);

  const platform = useMemo<LauncherPlatform>(() => ({
    systemEntries,
    defaultDockEntryIds: DEFAULT_LAUNCHER_DOCK_ENTRY_IDS,
    loadApps: () => window.hyperBrowser.requestAppsData(),
    openApp: (app) => {
      window.hyperBrowser.openApp(app.id);
    },
    openStandaloneApp: (app) => {
      window.hyperBrowser.openStandaloneApp(app.id);
    },
    openSystem: (action) => {
      if (action === "bookmarks") window.hyperBrowser.showBookmarks();
      if (action === "history") window.hyperBrowser.showHistory();
      if (action === "extensions") window.hyperBrowser.showExtensions();
    },
    deleteApp: async (app) => {
      const confirmed = await requestDeleteConfirmation(app);
      if (!confirmed) throw createAbortError();
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.delete", { id: app.id });
      return items;
    },
    saveApp: async (app, changes) => {
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.save", {
        ...app,
        ...changes,
        id: app.id,
      });
      return items;
    },
    chooseAppIcon: (app) => window.hyperBrowser.chooseAppIcon(app.id),
    pinApp: (app) => {
      window.hyperBrowser.pinApp(app.id);
    },
    updateAppIcon: async (app, iconDataUrl) => {
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.save", {
        ...app,
        id: app.id,
        iconDataUrl,
        iconSource: iconDataUrl ? "custom" : "site",
      });
      return items;
    },
  }), [systemEntries]);

  return (
    <>
      <LauncherPage
        labels={launcherLabels}
        platform={platform}
        layoutStorage={storage}
        refreshToken={webDavSync.refreshToken}
        previewLayoutMode="compact"
        onLayoutChanged={webDavSync.onLayoutChanged}
        topActions={webDavSync.topActions}
        variant="mobile"
      />
      {webDavSync.settingsDialog}
      {pendingDeleteApp && (
        <div className="confirm-scrim home-confirm-scrim" onClick={() => finishDeleteConfirmation(false)}>
          <section
            aria-describedby="home-delete-confirm-message"
            aria-labelledby="home-delete-confirm-title"
            aria-modal="true"
            className="confirm-dialog"
            onClick={(event) => event.stopPropagation()}
            role="dialog"
          >
            <h2 id="home-delete-confirm-title">{t("home.deleteAppConfirmTitle")}</h2>
            <p id="home-delete-confirm-message">
              {t("home.deleteAppConfirmMessage", { name: displayAppName(pendingDeleteApp) })}
            </p>
            <div className="confirm-actions">
              <button type="button" onClick={() => finishDeleteConfirmation(false)}>{t("common.cancel")}</button>
              <button className="danger" type="button" onClick={() => finishDeleteConfirmation(true)}>
                {t("home.deleteAppConfirmAction")}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}

function createAbortError(): Error {
  const error = new Error("WebApp deletion canceled.");
  error.name = "AbortError";
  return error;
}

function displayAppName(app: LauncherApp): string {
  const name = app.name.trim();
  if (name) return name;
  try {
    return new URL(app.startUrl).hostname.replace(/^www\./, "") || app.startUrl;
  } catch {
    return app.startUrl;
  }
}

function isWebDavConfigured(settings: BrowserSettings): boolean {
  return settings.webDavSyncUrl.trim().length > 0;
}

function settingsToDialogValues(settings: BrowserSettings): SyncSettingsDialogValues {
  return {
    webDavUrl: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    deviceName: settings.webDavSyncDeviceName,
    deviceId: settings.webDavSyncDeviceId,
  };
}

function normalizeDialogValues(values: SyncSettingsDialogValues): SyncSettingsDialogValues {
  return {
    ...values,
    webDavUrl: values.webDavUrl.trim(),
    username: values.username.trim(),
    deviceName: "Android",
  };
}

function isHttpUrl(value: string) {
  try {
    const protocol = new URL(value).protocol;
    return protocol === "https:" || protocol === "http:";
  } catch {
    return false;
  }
}

function webDavSyncSummary(result: WebDavSyncResult): string {
  return formatSyncResult(result, {
    complete: ({ bookmarks, webApps, deleted }) => t("settings.webDavSyncComplete", {
      bookmarks,
      webApps,
      deleted,
    }),
    pending: (pending) => t("settings.webDavSyncPending", { pending }),
  });
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HomePage />
  </React.StrictMode>
);
