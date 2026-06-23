import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { syncLauncherLayout } from "@hyper-launcher/webdav-layout";
import "../hyper-browser";
import "../styles.css";
import { getLocalePreference, matchesI18nText, setLocalePreference, t, type LocalePreference } from "../i18n";
import type { BatteryOptimizationState, BrowserSettings, UpdateCheckResult, UpdateDownloadState, WebDavSyncResult, WebDavSyncSettings } from "../hyper-browser";
import { createLauncherLayoutStorage } from "../launcher-layout-storage";

function SettingsPage() {
  const [query, setQuery] = useState("");
  const [languagePreference, setLanguagePreference] = useState<LocalePreference>(() => getLocalePreference());
  const [settings, setSettings] = useState<BrowserSettings | null>(null);
  const [customDraft, setCustomDraft] = useState("");
  const [customDirty, setCustomDirty] = useState(false);
  const [dohDraft, setDohDraft] = useState("");
  const [dohDirty, setDohDirty] = useState(false);
  const [webDavDraft, setWebDavDraft] = useState<WebDavSyncSettings>({
    webDavSyncEnabled: false,
    webDavSyncUrl: "",
    webDavSyncUsername: "",
    webDavSyncPassword: "",
    webDavSyncDeviceName: "",
  });
  const [webDavDirty, setWebDavDirty] = useState(false);
  const [webDavSyncing, setWebDavSyncing] = useState(false);
  const [customError, setCustomError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [updateResult, setUpdateResult] = useState<UpdateCheckResult | null>(null);
  const [updateDownload, setUpdateDownload] = useState<UpdateDownloadState | null>(null);
  const [batteryOptimization, setBatteryOptimization] = useState<BatteryOptimizationState | null>(null);
  const [updateChecking, setUpdateChecking] = useState(false);
  const [batteryMessage, setBatteryMessage] = useState("");
  const [privacyMessage, setPrivacyMessage] = useState("");
  const [privacyError, setPrivacyError] = useState("");
  const [webDavMessage, setWebDavMessage] = useState("");
  const [webDavError, setWebDavError] = useState("");
  const [updateMessage, setUpdateMessage] = useState("");
  const [backupMessage, setBackupMessage] = useState("");
  const [searchEngineExpanded, setSearchEngineExpanded] = useState(false);
  const [toolbarExpanded, setToolbarExpanded] = useState(false);
  const [languageExpanded, setLanguageExpanded] = useState(false);
  const [tabBehaviorExpanded, setTabBehaviorExpanded] = useState(false);
  const [httpDnsExpanded, setHttpDnsExpanded] = useState(false);
  const [privacyExpanded, setPrivacyExpanded] = useState(false);
  const [webDavExpanded, setWebDavExpanded] = useState(false);
  const [backupExpanded, setBackupExpanded] = useState(false);
  const [backgroundRuntimeExpanded, setBackgroundRuntimeExpanded] = useState(false);
  const [updateSettingsExpanded, setUpdateSettingsExpanded] = useState(false);
  const customInputRef = useRef<HTMLInputElement | null>(null);
  const didAutoCheckUpdateRef = useRef(false);
  const launcherLayoutStorage = useMemo(() => createLauncherLayoutStorage(), []);
  const showSearchEngine = useMemo(() => {
    return matchesI18nText("settings.searchEngineTerms", query);
  }, [query]);
  const showToolbarPosition = useMemo(() => {
    return matchesI18nText("settings.toolbarTerms", query);
  }, [query]);
  const showLanguageSettings = useMemo(() => {
    return matchesI18nText("settings.languageTerms", query);
  }, [query]);
  const showTabBehavior = useMemo(() => {
    return matchesI18nText("settings.newTabTerms", query);
  }, [query]);
  const showBackgroundRuntime = useMemo(() => {
    return matchesI18nText("settings.backgroundRuntimeTerms", query);
  }, [query]);
  const showHttpDnsSettings = useMemo(() => {
    return matchesI18nText("settings.httpDnsTerms", query);
  }, [query]);
  const showPrivacySettings = useMemo(() => {
    return matchesI18nText("settings.privacyTerms", query);
  }, [query]);
  const showWebDavSyncSettings = useMemo(() => {
    return matchesI18nText("settings.webDavSyncTerms", query);
  }, [query]);
  const showBackupSettings = useMemo(() => {
    return matchesI18nText("settings.backupTerms", query);
  }, [query]);
  const showUpdateSettings = useMemo(() => {
    return matchesI18nText("settings.updateTerms", query);
  }, [query]);
  const hasSettingsQuery = query.trim() !== "";
  const searchEngineVisibleExpanded = searchEngineExpanded || hasSettingsQuery;
  const toolbarVisibleExpanded = toolbarExpanded || hasSettingsQuery;
  const languageVisibleExpanded = languageExpanded || hasSettingsQuery;
  const tabBehaviorVisibleExpanded = tabBehaviorExpanded || hasSettingsQuery;
  const httpDnsVisibleExpanded = httpDnsExpanded || hasSettingsQuery;
  const privacyVisibleExpanded = privacyExpanded || hasSettingsQuery;
  const webDavVisibleExpanded = webDavExpanded || hasSettingsQuery;
  const backupVisibleExpanded = backupExpanded || hasSettingsQuery;
  const backgroundRuntimeVisibleExpanded = backgroundRuntimeExpanded || hasSettingsQuery;
  const updateSettingsVisibleExpanded = updateSettingsExpanded || hasSettingsQuery;

  useEffect(() => {
    loadSettings();
    window.hyperBrowser.requestBatteryOptimizationState()
      .then((value) => setBatteryOptimization(value))
      .catch(() => setBatteryMessage(t("settings.batteryStateUnavailable")));
    if (!didAutoCheckUpdateRef.current) {
      didAutoCheckUpdateRef.current = true;
      checkUpdate(false);
    }
  }, []);

  function loadSettings() {
    setLoadError("");
    window.hyperBrowser.requestSettingsData()
      .then((value) => {
        const storedPreference = getLocalePreference();
        const resolvedPreference = value.localePreference === "default" && storedPreference !== "default"
          ? storedPreference
          : value.localePreference;
        const applySettings = (next: BrowserSettings) => {
          setSettings(next);
          setLanguagePreference(next.localePreference);
          setLocalePreference(next.localePreference);
          setCustomDraft(next.customSearchUrl);
          setCustomDirty(false);
          setDohDraft(next.dohProviderUrl);
          setDohDirty(false);
          setWebDavDraft({
            webDavSyncEnabled: next.webDavSyncEnabled,
            webDavSyncUrl: next.webDavSyncUrl,
            webDavSyncUsername: next.webDavSyncUsername,
            webDavSyncPassword: next.webDavSyncPassword,
            webDavSyncDeviceName: next.webDavSyncDeviceName,
          });
          setWebDavDirty(false);
        };
        if (resolvedPreference !== value.localePreference) {
          window.hyperBrowser.updateLocalePreference(resolvedPreference)
            .then(applySettings)
            .catch(() => applySettings({ ...value, localePreference: resolvedPreference }));
        } else {
          applySettings(value);
        }
      })
      .catch(() => setLoadError(t("settings.unavailable")));
  }

  useEffect(() => {
    if (!updateDownload || !isActiveDownloadState(updateDownload.status)) return;
    const timer = window.setInterval(() => {
      window.hyperBrowser.requestUpdateDownloadState()
        .then((state) => {
          setUpdateDownload(state);
          if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
            setUpdateMessage(state.message || updateDownloadMessage(state));
          }
        })
        .catch((error) => setUpdateMessage(error instanceof Error ? error.message : t("settings.updateStatusUnavailable")));
    }, 500);
    return () => window.clearInterval(timer);
  }, [updateDownload?.status]);

  function updateSearchEngine(searchEngineId: BrowserSettings["searchEngineId"], nextCustomUrl: string) {
    window.hyperBrowser.updateSearchEngine(searchEngineId, nextCustomUrl)
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
        setCustomDirty(false);
      });
  }

  function selectPreset(searchEngineId: "google" | "bing") {
    setCustomError("");
    updateSearchEngine(searchEngineId, settings?.customSearchUrl || "");
  }

  function focusCustomInput() {
    setCustomError("");
    window.setTimeout(() => customInputRef.current?.focus(), 0);
  }

  function commitCustomSearchUrl() {
    if (!settings) return;
    const nextCustomUrl = customDraft.trim();
    if (!nextCustomUrl) {
      setCustomError("");
      setCustomDirty(false);
      return;
    }
    if (!nextCustomUrl.includes("%s")) {
      setCustomError(t("settings.customUrlMissingToken"));
      return;
    }
    setCustomError("");
    window.hyperBrowser.updateSearchEngine("custom", nextCustomUrl)
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
        setCustomDirty(false);
      })
      .catch((error) => setCustomError(error instanceof Error ? error.message : t("settings.updateFailed")));
  }

  function updateToolbarPosition(toolbarPosition: BrowserSettings["toolbarPosition"]) {
    window.hyperBrowser.updateToolbarPosition(toolbarPosition)
      .then((value) => setSettings(value))
      .catch(() => setLoadError(t("settings.unavailable")));
  }

  function updateBackgroundVideoEnhancement(enabled: boolean) {
    setBatteryMessage("");
    window.hyperBrowser.updateBackgroundVideoEnhancement(enabled)
      .then((value) => {
        setSettings(value);
        setBatteryMessage(enabled ? t("settings.backgroundVideoEnabled") : t("settings.backgroundVideoDisabled"));
      })
      .catch(() => setBatteryMessage(t("settings.unavailable")));
  }

  function updateOpenNewTabsInCurrentTab(enabled: boolean) {
    window.hyperBrowser.updateOpenNewTabsInCurrentTab(enabled)
      .then((value) => setSettings(value))
      .catch(() => setLoadError(t("settings.unavailable")));
  }

  function updatePrivacySettings(patch: Partial<Pick<BrowserSettings, "dohEnabled" | "dohProviderUrl" | "httpsOnlyEnabled" | "privacyProtectionLevel">>) {
    if (!settings) return Promise.resolve<BrowserSettings | null>(null);
    const next = {
      dohEnabled: settings.dohEnabled,
      dohProviderUrl: settings.dohProviderUrl,
      httpsOnlyEnabled: settings.httpsOnlyEnabled,
      privacyProtectionLevel: settings.privacyProtectionLevel,
      ...patch,
    };
    const cleanUrl = next.dohProviderUrl.trim();
    if (!isHttpsUrl(cleanUrl)) {
      setPrivacyError(t("settings.dohHttpsRequired"));
      return Promise.resolve<BrowserSettings | null>(null);
    }
    setPrivacyError("");
    setPrivacyMessage("");
    const dnsChanged = patch.dohEnabled !== undefined || patch.dohProviderUrl !== undefined;
    return window.hyperBrowser.updatePrivacySettings({ ...next, dohProviderUrl: cleanUrl })
      .then((value) => {
        setSettings(value);
        setPrivacyMessage(dnsChanged
          ? t("settings.dnsSaved")
          : t("settings.settingsSaved"));
        return value;
      })
      .catch((error) => {
        setPrivacyError(error instanceof Error ? error.message : t("settings.privacyUnavailable"));
        return null;
      });
  }

  function isHttpsUrl(value: string) {
    try {
      return new URL(value).protocol === "https:";
    } catch {
      return false;
    }
  }

  function commitDohProviderUrl() {
    if (!settings || !dohDirty) return;
    updatePrivacySettings({ dohProviderUrl: dohDraft })
      .then((value) => {
        if (!value) return;
        setDohDraft(value.dohProviderUrl);
        setDohDirty(false);
      });
  }

  function updateWebDavDraft<K extends keyof WebDavSyncSettings>(key: K, value: WebDavSyncSettings[K]) {
    setWebDavDraft((draft) => ({ ...draft, [key]: value }));
    setWebDavDirty(true);
    setWebDavError("");
    setWebDavMessage("");
  }

  function isHttpUrl(value: string) {
    try {
      const protocol = new URL(value).protocol;
      return protocol === "https:" || protocol === "http:";
    } catch {
      return false;
    }
  }

  function commitWebDavSettings(): Promise<BrowserSettings | null> {
    const cleanUrl = webDavDraft.webDavSyncUrl.trim();
    if (webDavDraft.webDavSyncEnabled && !isHttpUrl(cleanUrl)) {
      setWebDavError(t("settings.webDavUrlRequired"));
      return Promise.resolve(null);
    }
    setWebDavError("");
    return window.hyperBrowser.updateWebDavSyncSettings({
      ...webDavDraft,
      webDavSyncUrl: cleanUrl,
      webDavSyncUsername: webDavDraft.webDavSyncUsername.trim(),
      webDavSyncDeviceName: webDavDraft.webDavSyncDeviceName.trim(),
    })
      .then((value) => {
        setSettings(value);
        setWebDavDraft({
          webDavSyncEnabled: value.webDavSyncEnabled,
          webDavSyncUrl: value.webDavSyncUrl,
          webDavSyncUsername: value.webDavSyncUsername,
          webDavSyncPassword: value.webDavSyncPassword,
          webDavSyncDeviceName: value.webDavSyncDeviceName,
        });
        setWebDavDirty(false);
        setWebDavMessage(t("settings.webDavSaved"));
        return value;
      })
      .catch((error) => {
        setWebDavError(error instanceof Error ? error.message : t("settings.webDavSaveFailed"));
        return null;
      });
  }

  function runWebDavSync() {
    setWebDavSyncing(true);
    setWebDavError("");
    setWebDavMessage(t("settings.webDavSyncing"));
    const commit = webDavDirty ? commitWebDavSettings() : Promise.resolve(settings);
    commit
      .then((value) => {
        if (!value) return null;
        return window.hyperBrowser.runWebDavSync();
      })
      .then(async (result) => {
        if (!result) return;
        if (result.settings) {
          setSettings(result.settings);
          setWebDavDraft({
            webDavSyncEnabled: result.settings.webDavSyncEnabled,
            webDavSyncUrl: result.settings.webDavSyncUrl,
            webDavSyncUsername: result.settings.webDavSyncUsername,
            webDavSyncPassword: result.settings.webDavSyncPassword,
            webDavSyncDeviceName: result.settings.webDavSyncDeviceName,
          });
          if (result.settings.webDavSyncEnabled && result.settings.webDavSyncUrl.trim()) {
            await syncLauncherLayout(launcherLayoutStorage, {
              webDavUrl: result.settings.webDavSyncUrl,
              username: result.settings.webDavSyncUsername,
              password: result.settings.webDavSyncPassword,
              deviceId: result.settings.webDavSyncDeviceId,
              deviceName: result.settings.webDavSyncDeviceName || "Hyper Browser Android",
              clientName: "hyper-browser-android",
            }, await launcherSyncOptions());
          }
        }
        setWebDavDirty(false);
        setWebDavMessage(webDavSyncSummary(result));
      })
      .catch((error) => {
        setWebDavError(error instanceof Error ? error.message : t("settings.webDavSyncFailed"));
        setWebDavMessage("");
      })
      .finally(() => setWebDavSyncing(false));
  }

  function webDavSyncSummary(result: WebDavSyncResult) {
    return t("settings.webDavSyncComplete", {
      bookmarks: result.bookmarkCount,
      webApps: result.webAppCount,
      deleted: result.deletedBookmarkCount + result.deletedWebAppCount,
    });
  }

  async function launcherSyncOptions(): Promise<{ availableEntryIds: string[] }> {
    const apps = await window.hyperBrowser.requestAppsData().catch(() => []);
    return { availableEntryIds: apps.map((app) => app.id) };
  }

  function webDavStatusLabel() {
    if (!settings?.webDavSyncEnabled) return t("settings.webDavOff");
    return settings.webDavSyncUrl ? t("settings.webDavConfigured") : t("settings.webDavNeedsSetup");
  }

  function openBatteryOptimizationSettings() {
    setBatteryMessage("");
    window.hyperBrowser.openBatteryOptimizationSettings()
      .then((state) => {
        setBatteryOptimization(state);
        if (!state.opened) {
          setBatteryMessage(t("settings.batteryManualHelp"));
        }
      })
      .catch((error) => setBatteryMessage(error instanceof Error ? error.message : t("settings.batteryOpenFailed")));
  }

  function exportBackup() {
    setBackupMessage(t("settings.openingSaveLocation"));
    window.hyperBrowser.exportBackup()
      .then((result) => setBackupMessage(result.message || t("settings.chooseBackupSaveLocation")))
      .catch((error) => setBackupMessage(error instanceof Error ? error.message : t("settings.exportBackupFailed")));
  }

  function importBackup() {
    setBackupMessage(t("settings.openingBackupPicker"));
    window.hyperBrowser.importBackup()
      .then((result) => setBackupMessage(result.message || t("settings.chooseBackupFile")))
      .catch((error) => setBackupMessage(error instanceof Error ? error.message : t("settings.importBackupFailed")));
  }

  function batteryOptimizationLabel() {
    return batteryOptimization?.ignoringBatteryOptimizations ? t("settings.batteryAllowed") : t("settings.batteryRestricted");
  }

  function toolbarPositionLabel(toolbarPosition?: BrowserSettings["toolbarPosition"]) {
    return toolbarPosition === "bottom" ? t("settings.bottom") : t("settings.top");
  }

  function languagePreferenceLabel(value: LocalePreference = languagePreference) {
    if (value === "zh") return t("settings.languageChinese");
    if (value === "en") return t("settings.languageEnglish");
    return t("settings.languageDefault");
  }

  function updateLanguagePreference(value: LocalePreference) {
    setLanguagePreference(value);
    setLocalePreference(value);
    window.hyperBrowser.updateLocalePreference(value)
      .then((nextSettings) => {
        setSettings(nextSettings);
        setLanguagePreference(nextSettings.localePreference);
        setLocalePreference(nextSettings.localePreference);
        reloadForLanguagePreference(nextSettings.localePreference);
      })
      .catch(() => setLoadError(t("settings.unavailable")));
  }

  function reloadForLanguagePreference(value: LocalePreference) {
    const baseUrl = window.location.href.split("#")[0];
    window.location.href = value === "default" ? baseUrl : `${baseUrl}#locale=${encodeURIComponent(value)}`;
  }

  function tabBehaviorLabel() {
    return settings?.openNewTabsInCurrentTab ? t("settings.currentTabValue") : t("settings.newTabValue");
  }

  function privacyStatusLabel() {
    if (!settings) return t("settings.standard");
    if (settings.privacyProtectionLevel === "none") return t("settings.none");
    return settings.privacyProtectionLevel === "strict" ? t("settings.strict") : t("settings.standard");
  }

  function httpDnsStatusLabel() {
    if (!settings) return t("settings.httpDnsDisabled");
    const active = [
      settings.dohEnabled,
      settings.httpsOnlyEnabled,
    ].filter(Boolean).length;
    return active > 0 ? t("settings.httpDnsActive", { count: active }) : t("settings.httpDnsDisabled");
  }

  function checkUpdate(ignoreSkipped = false) {
    setUpdateChecking(true);
    setUpdateMessage(t("settings.checkingUpdate"));
    window.hyperBrowser.checkUpdate(ignoreSkipped)
      .then((result) => {
        setUpdateResult(result);
        setUpdateMessage(result.message || updateStatusLabel(result));
        return window.hyperBrowser.requestUpdateDownloadState()
          .then((state) => {
            if (result.update && state.versionCode === result.update.versionCode && state.status !== "idle") {
              setUpdateDownload(state);
              if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
                setUpdateMessage(state.message || updateDownloadMessage(state));
              }
            }
          })
          .catch(() => undefined);
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : t("settings.checkUpdateFailed")))
      .finally(() => setUpdateChecking(false));
  }

  function installUpdate() {
    const update = updateResult?.update;
    if (!update) return;
    setUpdateMessage(t("settings.updateStarted"));
    setUpdateDownload({
      status: "preparing",
      versionCode: update.versionCode,
      versionName: update.versionName,
      bytesDownloaded: 0,
      totalBytes: update.asset.sizeBytes || 0,
      message: t("settings.preparingUpdate"),
    });
    window.hyperBrowser.installUpdate(update.versionCode)
      .then((state) => {
        setUpdateDownload(state);
        if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
          setUpdateMessage(state.message || updateDownloadMessage(state));
        }
      })
      .catch((error) => {
        setUpdateDownload(null);
        setUpdateMessage(error instanceof Error ? error.message : t("settings.updateFailed"));
      });
  }

  function skipUpdate() {
    const update = updateResult?.update;
    if (!update) return;
    window.hyperBrowser.skipUpdate(update.versionCode)
      .then(() => {
        setUpdateResult({ ...updateResult, status: "skipped", skippedVersionCode: update.versionCode });
        setUpdateMessage(t("settings.skippedVersion", { version: update.versionName }));
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : t("settings.skipFailed")));
  }

  function clearSkippedUpdate() {
    window.hyperBrowser.clearSkippedUpdate()
      .then(() => {
        setUpdateResult((result) => result ? { ...result, skippedVersionCode: 0, status: result.update ? "available" : result.status } : result);
        setUpdateMessage(t("settings.skipCleared"));
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : t("settings.clearSkipFailed")));
  }

  function updateStatusLabel(result: UpdateCheckResult) {
    switch (result.status) {
      case "available": return t("settings.updateAvailable");
      case "skipped": return t("settings.updateSkipped");
      case "upToDate": return t("settings.upToDate");
      case "unsupported": return t("settings.updateUnsupported");
      default: return t("settings.checkUpdateFailed");
    }
  }

  function formatBytes(value?: number) {
    if (!value || value <= 0) return "";
    if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
    if (value >= 1024) return `${(value / 1024).toFixed(0)} KB`;
    return `${value} B`;
  }

  function isActiveDownloadState(status: UpdateDownloadState["status"]) {
    return status === "preparing" || status === "downloading" || status === "verifying";
  }

  function updateProgressPercent(state: UpdateDownloadState) {
    if (!state.totalBytes || state.totalBytes <= 0) return 0;
    return Math.min(100, Math.max(0, Math.round((state.bytesDownloaded / state.totalBytes) * 100)));
  }

  function updateActionLabel() {
    if (updateDownload && isActiveDownloadState(updateDownload.status)) {
      if (updateDownload.status === "downloading") return t("settings.downloadingPercent", { percent: updateProgressPercent(updateDownload) });
      if (updateDownload.status === "verifying") return t("settings.verifyingShort");
      return t("settings.preparingShort");
    }
    if (updateDownload?.status === "permissionRequired") return t("settings.retryAfterPermission");
    if (updateDownload?.status === "ready") return t("settings.installUpdate");
    if (updateDownload?.status === "error") return t("settings.retryDownload");
    return updateResult?.status === "skipped" ? t("settings.updateAnyway") : t("settings.updateNow");
  }

  function updateDownloadMessage(state: UpdateDownloadState) {
    switch (state.status) {
      case "preparing": return t("settings.preparingUpdate");
      case "permissionRequired": return t("settings.installPermissionRequired");
      case "downloading": return t("settings.downloadingUpdate", { percent: updateProgressPercent(state) });
      case "verifying": return t("settings.verifyingPackage");
      case "ready": return t("settings.downloadReady");
      case "error": return state.message || t("settings.updateDownloadFailed");
      default: return "";
    }
  }

  return (
    <main className="settings-page">
      <header className="settings-header">
        <button className="settings-back" type="button" aria-label={t("common.back")} onClick={() => window.history.back()}>‹</button>
        <h1>{t("settings.title")}</h1>
        <button className="settings-help" type="button" aria-label={t("settings.help")}>?</button>
      </header>

      <div className="settings-search" role="search">
        <span className="settings-search-icon" aria-hidden="true">⌕</span>
        <input
          type="search"
          aria-label={t("settings.searchPlaceholder")}
          placeholder={t("settings.searchPlaceholder")}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
        />
        {query && (
          <button className="page-search-clear" type="button" aria-label={t("common.clear")} onClick={() => setQuery("")}>
            ×
          </button>
        )}
      </div>

      <section className="settings-section">
        <h2>{t("settings.basic")}</h2>
        {showSearchEngine ? (
          <div className="settings-card search-engine-card">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={searchEngineVisibleExpanded}
              onClick={() => setSearchEngineExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.searchEngine")}</span>
              <span className="settings-row-value">{settings?.searchEngineName || "Google"}</span>
            </button>
            {searchEngineVisibleExpanded && (
              <div className="settings-options">
                <button
                  className={settings?.searchEngineId === "google" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => selectPreset("google")}
                >
                  <span>Google</span>
                  <span>google.com</span>
                </button>
                <button
                  className={settings?.searchEngineId === "bing" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => selectPreset("bing")}
                >
                  <span>Bing</span>
                  <span>bing.com</span>
                </button>
                <div className="custom-search-form">
                  <button
                    className={settings?.searchEngineId === "custom" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={focusCustomInput}
                  >
                    <span>{t("settings.custom")}</span>
                    <span>{t("settings.customKeywordHelp")}</span>
                  </button>
                  <div className="settings-field-row">
                    <span className="settings-field-title">
                      <span>{t("common.url")}</span>
                      {customDirty && <span>{t("settings.unsaved")}</span>}
                    </span>
                    <span className="settings-input-action-row">
                      <input
                        ref={customInputRef}
                        type="text"
                        inputMode="url"
                        placeholder="https://example.com/search?q=%s"
                        value={customDraft}
                        disabled={!settings}
                        onChange={(event) => {
                          setCustomDraft(event.currentTarget.value);
                          setCustomDirty(true);
                          setCustomError("");
                        }}
                        onBlur={commitCustomSearchUrl}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.currentTarget.blur();
                          }
                        }}
                      />
                      <button
                        className="settings-field-action"
                        type="button"
                        disabled={!settings || !customDraft.trim()}
                        onClick={commitCustomSearchUrl}
                      >
                        {t("common.save")}
                      </button>
                    </span>
                  </div>
                  {customError && <p className="settings-inline-error">{customError}</p>}
                </div>
              </div>
            )}
          </div>
        ) : !showToolbarPosition && !showLanguageSettings && !showTabBehavior && !showHttpDnsSettings && !showPrivacySettings && !showWebDavSyncSettings && !showBackupSettings && !showBackgroundRuntime && !showUpdateSettings ? (
          <div className="settings-empty">{t("settings.noMatches")}</div>
        ) : null}
        {showToolbarPosition && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={toolbarVisibleExpanded}
              onClick={() => setToolbarExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.toolbar")}</span>
              <span className="settings-row-value">{toolbarPositionLabel(settings?.toolbarPosition)}</span>
            </button>
            {toolbarVisibleExpanded && (
              <div className="settings-options">
                <button
                  className={settings?.toolbarPosition !== "bottom" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateToolbarPosition("top")}
                >
                  <span>{t("settings.top")}</span>
                  <span>{t("settings.toolbarTopHelp")}</span>
                </button>
                <button
                  className={settings?.toolbarPosition === "bottom" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateToolbarPosition("bottom")}
                >
                  <span>{t("settings.bottom")}</span>
                  <span>{t("settings.toolbarBottomHelp")}</span>
                </button>
              </div>
            )}
          </div>
        )}
        {showLanguageSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={languageVisibleExpanded}
              onClick={() => setLanguageExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.language")}</span>
              <span className="settings-row-value">{languagePreferenceLabel()}</span>
            </button>
            {languageVisibleExpanded && (
              <div className="settings-options">
                <button
                  className={languagePreference === "zh" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateLanguagePreference("zh")}
                >
                  <span>{t("settings.languageChinese")}</span>
                </button>
                <button
                  className={languagePreference === "en" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateLanguagePreference("en")}
                >
                  <span>{t("settings.languageEnglish")}</span>
                </button>
                <button
                  className={languagePreference === "default" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateLanguagePreference("default")}
                >
                  <span>{t("settings.languageDefault")}</span>
                </button>
              </div>
            )}
          </div>
        )}
        {showTabBehavior && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={tabBehaviorVisibleExpanded}
              onClick={() => setTabBehaviorExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.newTab")}</span>
              <span className="settings-row-value">{tabBehaviorLabel()}</span>
            </button>
            {tabBehaviorVisibleExpanded && (
              <>
                <div className="settings-options">
                  <button
                    className={!settings?.openNewTabsInCurrentTab ? "settings-option selected" : "settings-option"}
                    type="button"
                    disabled={!settings}
                    onClick={() => updateOpenNewTabsInCurrentTab(false)}
                  >
                    <span>{t("settings.newTabValue")}</span>
                    <span>{t("settings.keepPopupHelp")}</span>
                  </button>
                  <button
                    className={settings?.openNewTabsInCurrentTab ? "settings-option selected" : "settings-option"}
                    type="button"
                    disabled={!settings}
                    onClick={() => updateOpenNewTabsInCurrentTab(true)}
                  >
                    <span>{t("settings.currentTabValue")}</span>
                    <span>{t("settings.currentTabHelp")}</span>
                  </button>
                </div>
              </>
            )}
          </div>
        )}
        {showHttpDnsSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={httpDnsVisibleExpanded}
              onClick={() => setHttpDnsExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">HTTP / DNS</span>
              <span className="settings-row-value">{httpDnsStatusLabel()}</span>
            </button>
            {httpDnsVisibleExpanded && (
              <>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">DNS over HTTPS</span>
                    <span className="settings-toggle-description">{t("settings.encryptedDnsHelp")}</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.dohEnabled}
                    disabled={!settings}
                    onChange={(event) => updatePrivacySettings({ dohEnabled: event.currentTarget.checked })}
                  />
                </label>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">{t("settings.dohAddress")}</span>
                    {dohDirty && <span>{t("settings.unsaved")}</span>}
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="text"
                      inputMode="url"
                      value={dohDraft}
                      disabled={!settings}
                      onChange={(event) => {
                        setDohDraft(event.currentTarget.value);
                        setDohDirty(true);
                        setPrivacyError("");
                        setPrivacyMessage("");
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          event.currentTarget.blur();
                          commitDohProviderUrl();
                        }
                      }}
                    />
                    <button
                      className="settings-field-action"
                      type="button"
                      disabled={!settings || !dohDirty}
                      onClick={commitDohProviderUrl}
                    >
                      {t("common.save")}
                    </button>
                  </span>
                </div>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">HTTPS-Only</span>
                    <span className="settings-toggle-description">{t("settings.httpsOnlyHelp")}</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.httpsOnlyEnabled}
                    disabled={!settings}
                    onChange={(event) => updatePrivacySettings({ httpsOnlyEnabled: event.currentTarget.checked })}
                  />
                </label>
                <p className="settings-message">{t("settings.httpDnsHelp")}</p>
                {privacyError && <p className="settings-inline-error">{privacyError}</p>}
                {privacyMessage && <p className="settings-message">{privacyMessage}</p>}
              </>
            )}
          </div>
        )}
        {showPrivacySettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={privacyVisibleExpanded}
              onClick={() => setPrivacyExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.privacy")}</span>
              <span className="settings-row-value">{privacyStatusLabel()}</span>
            </button>
            {privacyVisibleExpanded && (
              <>
                <div className="settings-options">
                  <button
                    className={settings?.privacyProtectionLevel === "none" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "none" })}
                  >
                    <span>{t("settings.noTrackingProtection")}</span>
                    <span>{t("settings.noTrackingProtectionHelp")}</span>
                  </button>
                  <button
                    className={settings?.privacyProtectionLevel === "standard" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "standard" })}
                  >
                    <span>{t("settings.standardTrackingProtection")}</span>
                    <span>{t("settings.standardTrackingProtectionHelp")}</span>
                  </button>
                  <button
                    className={settings?.privacyProtectionLevel === "strict" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "strict" })}
                  >
                    <span>{t("settings.strictTrackingProtection")}</span>
                    <span>{t("settings.strictTrackingProtectionHelp")}</span>
                  </button>
                </div>
                <p className="settings-message">{t("settings.privacyHelp")}</p>
                {privacyMessage && <p className="settings-message">{privacyMessage}</p>}
              </>
            )}
          </div>
        )}
        {showWebDavSyncSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={webDavVisibleExpanded}
              onClick={() => setWebDavExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.webDavSync")}</span>
              <span className="settings-row-value">{webDavStatusLabel()}</span>
            </button>
            {webDavVisibleExpanded && (
              <>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">{t("settings.webDavEnable")}</span>
                    <span className="settings-toggle-description">{t("settings.webDavEnableHelp")}</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={webDavDraft.webDavSyncEnabled}
                    disabled={!settings}
                    onChange={(event) => updateWebDavDraft("webDavSyncEnabled", event.currentTarget.checked)}
                  />
                </label>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">{t("settings.webDavUrl")}</span>
                    {webDavDirty && <span>{t("settings.unsaved")}</span>}
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="text"
                      inputMode="url"
                      placeholder="https://example.com/dav"
                      value={webDavDraft.webDavSyncUrl}
                      disabled={!settings}
                      onChange={(event) => updateWebDavDraft("webDavSyncUrl", event.currentTarget.value)}
                    />
                  </span>
                </div>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">{t("settings.webDavUsername")}</span>
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="text"
                      autoComplete="username"
                      value={webDavDraft.webDavSyncUsername}
                      disabled={!settings}
                      onChange={(event) => updateWebDavDraft("webDavSyncUsername", event.currentTarget.value)}
                    />
                  </span>
                </div>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">{t("settings.webDavPassword")}</span>
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="password"
                      autoComplete="current-password"
                      value={webDavDraft.webDavSyncPassword}
                      disabled={!settings}
                      onChange={(event) => updateWebDavDraft("webDavSyncPassword", event.currentTarget.value)}
                    />
                  </span>
                </div>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">{t("settings.webDavDeviceName")}</span>
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="text"
                      placeholder="Android phone"
                      value={webDavDraft.webDavSyncDeviceName}
                      disabled={!settings}
                      onChange={(event) => updateWebDavDraft("webDavSyncDeviceName", event.currentTarget.value)}
                    />
                  </span>
                </div>
                {settings?.webDavSyncDeviceId && (
                  <p className="settings-message">{t("settings.webDavDeviceId", { deviceId: settings.webDavSyncDeviceId })}</p>
                )}
                <div className="settings-actions">
                  <button className="settings-action" type="button" disabled={!settings || !webDavDirty} onClick={commitWebDavSettings}>
                    {t("common.save")}
                  </button>
                  <button
                    className="settings-action primary"
                    type="button"
                    disabled={!settings || webDavSyncing || !webDavDraft.webDavSyncUrl.trim()}
                    onClick={runWebDavSync}
                  >
                    {webDavSyncing ? t("settings.webDavSyncingShort") : t("settings.webDavSyncNow")}
                  </button>
                </div>
                <p className="settings-message">{t("settings.webDavHelp")}</p>
                {webDavError && <p className="settings-inline-error">{webDavError}</p>}
                {webDavMessage && <p className="settings-message">{webDavMessage}</p>}
              </>
            )}
          </div>
        )}
        {showBackupSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={backupVisibleExpanded}
              onClick={() => setBackupExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.backup")}</span>
              <span className="settings-row-value">{t("settings.backupValue")}</span>
            </button>
            {backupVisibleExpanded && (
              <>
                <div className="settings-actions">
                  <button className="settings-action primary" type="button" onClick={exportBackup}>
                    {t("settings.exportJson")}
                  </button>
                  <button className="settings-action" type="button" onClick={importBackup}>
                    {t("settings.importJson")}
                  </button>
                </div>
                <p className="settings-message">{t("settings.backupHelp")}</p>
                {backupMessage && <p className="settings-message">{backupMessage}</p>}
              </>
            )}
          </div>
        )}
        {showBackgroundRuntime && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={backgroundRuntimeVisibleExpanded}
              onClick={() => setBackgroundRuntimeExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.backgroundRuntime")}</span>
              <span className="settings-row-value">{batteryOptimizationLabel()}</span>
            </button>
            {backgroundRuntimeVisibleExpanded && (
              <>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">{t("settings.backgroundVideoEnhancement")}</span>
                    <span className="settings-toggle-description">{t("settings.backgroundVideoHelp")}</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.backgroundVideoEnhancementEnabled}
                    disabled={!settings}
                    onChange={(event) => updateBackgroundVideoEnhancement(event.currentTarget.checked)}
                  />
                </label>
                <p className="settings-message">
                  {t("settings.batteryHelp")}
                </p>
                <div className="settings-actions">
                  <button className="settings-action" type="button" onClick={openBatteryOptimizationSettings}>
                    {t("settings.openBatterySettings")}
                  </button>
                </div>
                {batteryMessage && <p className="settings-message">{batteryMessage}</p>}
              </>
            )}
          </div>
        )}
        {showUpdateSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={updateSettingsVisibleExpanded}
              onClick={() => setUpdateSettingsExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">{t("settings.update")}</span>
              <span className="settings-row-value">
                {updateResult ? `${updateResult.currentVersionName} · ${updateStatusLabel(updateResult)}` : t("settings.githubRelease")}
              </span>
            </button>
            {updateSettingsVisibleExpanded && (
              <>
                {updateResult?.update && (
                  <div className="settings-options">
                    <div className="settings-option">
                      <span>{updateResult.update.versionName}</span>
                      <span>
                        {updateResult.update.asset.abi}
                        {formatBytes(updateResult.update.asset.sizeBytes) ? ` · ${formatBytes(updateResult.update.asset.sizeBytes)}` : ""}
                      </span>
                    </div>
                    {updateResult.update.notes && (
                      <p className="settings-message">{updateResult.update.notes}</p>
                    )}
                  </div>
                )}
                <div className="settings-actions">
                  <button className="settings-action" type="button" disabled={updateChecking} onClick={() => checkUpdate(false)}>
                    {updateChecking ? t("settings.checking") : t("settings.checkUpdate")}
                  </button>
                  {updateResult?.status === "skipped" && (
                    <button className="settings-action" type="button" onClick={() => checkUpdate(true)}>{t("settings.viewAnyway")}</button>
                  )}
                  {updateResult?.update && (
                    <button
                      className="settings-action primary"
                      type="button"
                      disabled={!!updateDownload && isActiveDownloadState(updateDownload.status)}
                      onClick={installUpdate}
                    >
                      {updateActionLabel()}
                    </button>
                  )}
                  {updateResult?.update && updateResult.status !== "skipped" && (
                    <button className="settings-action" type="button" onClick={skipUpdate}>{t("settings.skipVersion")}</button>
                  )}
                  {(updateResult?.skippedVersionCode ?? 0) > 0 && (
                    <button className="settings-action" type="button" onClick={clearSkippedUpdate}>{t("settings.clearSkip")}</button>
                  )}
                </div>
                {updateDownload && updateDownload.status !== "idle" && (
                  <div className="settings-update-progress">
                    <div className="settings-update-progress-track">
                      <div
                        className="settings-update-progress-fill"
                        style={{ width: `${updateProgressPercent(updateDownload)}%` }}
                      />
                    </div>
                    <div className="settings-update-progress-text">
                      <span>{updateDownloadMessage(updateDownload) || (updateResult ? updateStatusLabel(updateResult) : t("settings.processingUpdate"))}</span>
                      <span>
                        {formatBytes(updateDownload.bytesDownloaded)}
                        {updateDownload.totalBytes > 0 ? ` / ${formatBytes(updateDownload.totalBytes)}` : ""}
                      </span>
                    </div>
                  </div>
                )}
                {updateMessage && (!updateDownload || !isActiveDownloadState(updateDownload.status)) && (
                  <p className="settings-message">{updateMessage}</p>
                )}
              </>
            )}
          </div>
        )}
        {loadError && (
          <>
            <p className="settings-message">{loadError}</p>
            <div className="settings-actions">
              <button className="settings-action" type="button" onClick={loadSettings}>{t("settings.retryLoad")}</button>
            </div>
          </>
        )}
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <SettingsPage />
  </React.StrictMode>
);
