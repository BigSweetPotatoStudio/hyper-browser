import React, { useCallback, useEffect, useRef, useState } from "react";
import { LauncherSyncActions, type LauncherSyncActionLabels, type LauncherSyncState } from "@hyper-launcher";
import { shouldRefreshLauncherAfterSync, type SyncRefreshSignal } from "./index";
import {
  SyncSettingsDialog,
  type SyncSettingsDialogAction,
  type SyncSettingsDialogLabels,
  type SyncSettingsDialogResult,
  type SyncSettingsDialogValues,
} from "./settings-dialog";

export type LauncherWebDavSyncLabels = {
  actions: LauncherSyncActionLabels;
  dialog: SyncSettingsDialogLabels;
  syncingMessage: string;
  needsSetupMessage: string;
  syncFailedMessage: string;
};

export type LauncherWebDavSyncEvent<TResult> = {
  type: "remote.synced" | "launcher.changed";
  syncResult?: TResult | null;
};

export type LauncherWebDavSyncOptions<TSettings, TResult extends SyncRefreshSignal> = {
  labels: LauncherWebDavSyncLabels;
  loadSettings: () => Promise<TSettings>;
  saveSettings: (values: SyncSettingsDialogValues) => Promise<TSettings>;
  isConfigured: (settings: TSettings) => boolean;
  settingsToDialogValues: (settings: TSettings) => SyncSettingsDialogValues;
  normalizeDialogValues?: (values: SyncSettingsDialogValues) => SyncSettingsDialogValues;
  runSync: (options?: { mode?: SyncSettingsDialogAction }) => Promise<TResult>;
  scheduleSyncSoon: () => Promise<void> | void;
  summarizeResult: (result: TResult) => string;
  resultSettings?: (result: TResult) => TSettings | null | undefined;
  shouldRefreshLauncher?: (result: TResult) => boolean;
  subscribeSyncEvents: (listener: (event: LauncherWebDavSyncEvent<TResult>) => void) => () => void;
  beforeManualSync?: () => Promise<void> | void;
  beforeScheduleSync?: () => Promise<void> | void;
  autoSyncDebounceMs?: number;
  showFolderTitle?: boolean;
};

export type LauncherWebDavSyncController = {
  refreshToken: number;
  topActions: React.ReactNode;
  settingsDialog: React.ReactNode;
  onLayoutChanged: () => void;
};

export type LauncherWebDavSyncTranslate = (key: string, values?: Record<string, unknown>) => string;

export function createLauncherWebDavSyncLabels(t: LauncherWebDavSyncTranslate): LauncherWebDavSyncLabels {
  return {
    actions: {
      settings: t("settings.title"),
      sync: t("settings.webDavSyncNow"),
      syncing: t("settings.webDavSyncingShort"),
      syncTitle: t("settings.webDavSyncNow"),
      setupTitle: t("settings.webDavNeedsSetup"),
    },
    dialog: {
      title: t("settings.title"),
      close: t("common.close"),
      webDavAddress: t("settings.webDavUrl"),
      username: t("settings.webDavUsername"),
      password: t("settings.webDavPassword"),
      folderTitle: t("settings.webDavFolderTitle"),
      help: t("settings.webDavHelp"),
      useRemote: t("settings.webDavUseRemote"),
      useLocal: t("settings.webDavUseLocal"),
      syncing: t("settings.webDavSyncingShort"),
      loadFailed: t("settings.unavailable"),
      syncFailed: t("settings.webDavSyncFailed"),
      deviceId: (deviceId) => t("settings.webDavDeviceId", { deviceId }),
    },
    syncingMessage: t("settings.webDavSyncingShort"),
    needsSetupMessage: t("settings.webDavNeedsSetup"),
    syncFailedMessage: t("settings.webDavSyncFailed"),
  };
}

export const defaultLauncherWebDavSyncLabels = createLauncherWebDavSyncLabels((key, values) =>
  translateEnglishLauncherWebDavSyncLabel(key, values)
);

export function useLauncherWebDavSync<TSettings, TResult extends SyncRefreshSignal>(
  options: LauncherWebDavSyncOptions<TSettings, TResult>,
): LauncherWebDavSyncController {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);
  const syncRunning = useRef(false);
  const autoSyncTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    options.loadSettings()
      .then((settings) => setSettingsConfigured(options.isConfigured(settings)))
      .catch(() => undefined);
  }, [options]);

  const loadDialogValues = useCallback(async () => {
    return options.settingsToDialogValues(await options.loadSettings());
  }, [options]);

  const syncDialogValues = useCallback(async (
    values: SyncSettingsDialogValues,
    action: SyncSettingsDialogAction,
  ): Promise<SyncSettingsDialogResult> => {
    try {
      const savedSettings = await options.saveSettings(values);
      setSettingsConfigured(options.isConfigured(savedSettings));
      await options.beforeManualSync?.();
      const result = await options.runSync({ mode: action });
      const resultSettings = options.resultSettings?.(result);
      if (resultSettings) setSettingsConfigured(options.isConfigured(resultSettings));
      const message = options.summarizeResult(result);
      setSyncState("success");
      setSyncMessage(message);
      return {
        values: options.settingsToDialogValues(resultSettings || savedSettings),
        message,
      };
    } catch (error) {
      setSyncState("error");
      setSyncMessage(error instanceof Error ? error.message : options.labels.syncFailedMessage);
      throw error;
    }
  }, [options]);

  const runManualSync = useCallback(async () => {
    if (syncRunning.current) return;
    syncRunning.current = true;
    setSyncState("syncing");
    setSyncMessage(options.labels.syncingMessage);
    try {
      const settings = await options.loadSettings();
      const configured = options.isConfigured(settings);
      setSettingsConfigured(configured);
      if (!configured) {
        setSyncState("needs-settings");
        setSyncMessage(options.labels.needsSetupMessage);
        setSettingsOpen(true);
        return;
      }
      await options.beforeManualSync?.();
      const result = await options.runSync();
      const resultSettings = options.resultSettings?.(result);
      if (resultSettings) setSettingsConfigured(options.isConfigured(resultSettings));
      setSyncState("success");
      setSyncMessage(options.summarizeResult(result));
    } catch (error) {
      setSyncState("error");
      setSyncMessage(error instanceof Error ? error.message : options.labels.syncFailedMessage);
    } finally {
      syncRunning.current = false;
    }
  }, [options]);

  useEffect(() => {
    return options.subscribeSyncEvents((event) => {
      if (event.syncResult) {
        setSyncState("success");
        setSyncMessage(options.summarizeResult(event.syncResult));
        const shouldRefresh = options.shouldRefreshLauncher || shouldRefreshLauncherAfterSync;
        if (shouldRefresh(event.syncResult)) setLayoutRevision((current) => current + 1);
        return;
      }
      if (event.type === "launcher.changed") setLayoutRevision((current) => current + 1);
    });
  }, [options]);

  const scheduleSyncSoon = useCallback(() => {
    const run = () => {
      Promise.resolve(options.beforeScheduleSync?.())
        .then(() => options.scheduleSyncSoon())
        .catch((error) => console.warn("Unable to schedule launcher sync.", error));
    };

    const debounceMs = options.autoSyncDebounceMs || 0;
    if (debounceMs <= 0) {
      run();
      return;
    }
    if (autoSyncTimer.current !== null) clearTimeout(autoSyncTimer.current);
    autoSyncTimer.current = setTimeout(() => {
      autoSyncTimer.current = null;
      run();
    }, debounceMs);
  }, [options]);

  useEffect(() => () => {
    if (autoSyncTimer.current !== null) {
      clearTimeout(autoSyncTimer.current);
      autoSyncTimer.current = null;
    }
  }, []);

  return {
    refreshToken: layoutRevision,
    onLayoutChanged: scheduleSyncSoon,
    topActions: (
      <LauncherSyncActions
        labels={options.labels.actions}
        message={syncMessage}
        settingsConfigured={settingsConfigured}
        state={syncState}
        onOpenSettings={() => setSettingsOpen(true)}
        onSync={runManualSync}
      />
    ),
    settingsDialog: settingsOpen ? (
      <SyncSettingsDialog
        labels={options.labels.dialog}
        loadValues={loadDialogValues}
        normalizeValues={options.normalizeDialogValues}
        syncValues={syncDialogValues}
        showFolderTitle={options.showFolderTitle}
        onClose={() => setSettingsOpen(false)}
      />
    ) : null,
  };
}

function translateEnglishLauncherWebDavSyncLabel(key: string, values?: Record<string, unknown>): string {
  const labels: Record<string, string> = {
    "common.close": "Close",
    "settings.title": "Settings",
    "settings.unavailable": "Unable to load settings.",
    "settings.webDavUrl": "WebDAV address",
    "settings.webDavUsername": "Username",
    "settings.webDavPassword": "Password or app token",
    "settings.webDavFolderTitle": "Sync folder title",
    "settings.webDavDeviceId": "Device ID: {deviceId}",
    "settings.webDavHelp": "Remote data is stored under HyperBrowserSync/bookmarks.json, webapps.json, launcher.json, and manifest.json.",
    "settings.webDavUseRemote": "Use cloud data",
    "settings.webDavUseLocal": "Upload this device",
    "settings.webDavSyncNow": "Sync",
    "settings.webDavSyncingShort": "Syncing...",
    "settings.webDavSyncFailed": "Sync failed.",
    "settings.webDavNeedsSetup": "Set WebDAV URL first",
  };
  return (labels[key] || key).replace(/\{(\w+)\}/g, (match, name) => {
    const value = values?.[name];
    return value === undefined ? match : String(value);
  });
}
