import { useEffect, useState, type FormEvent } from "react";
import "./settings-dialog.css";

export type SyncSettingsDialogValues = {
  webDavUrl: string;
  username: string;
  password: string;
  folderTitle?: string;
  deviceName: string;
  deviceId?: string;
};

export type SyncSettingsDialogLabels = {
  title: string;
  close: string;
  webDavAddress: string;
  username: string;
  password: string;
  folderTitle: string;
  help: string;
  useRemote: string;
  useLocal: string;
  syncing: string;
  loadFailed: string;
  syncFailed: string;
  deviceId: (deviceId: string) => string;
};

export type SyncSettingsDialogResult = {
  values?: SyncSettingsDialogValues;
  message?: string;
};

export type SyncSettingsDialogAction = "pullRemote" | "pushLocal";

type Props = {
  labels: SyncSettingsDialogLabels;
  loadValues: () => Promise<SyncSettingsDialogValues>;
  syncValues: (values: SyncSettingsDialogValues, action: SyncSettingsDialogAction) => Promise<SyncSettingsDialogResult | void>;
  onClose: () => void;
  onSynced?: () => void;
  normalizeValues?: (values: SyncSettingsDialogValues) => SyncSettingsDialogValues;
  showFolderTitle?: boolean;
};

const emptyValues: SyncSettingsDialogValues = {
  webDavUrl: "",
  username: "",
  password: "",
  folderTitle: "",
  deviceName: "",
  deviceId: "",
};

export function SyncSettingsDialog(props: Props) {
  const [values, setValues] = useState<SyncSettingsDialogValues>(emptyValues);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busyAction, setBusyAction] = useState<SyncSettingsDialogAction | null>(null);
  const showFolderTitle = props.showFolderTitle !== false;

  useEffect(() => {
    let cancelled = false;
    props.loadValues()
      .then((loaded) => {
        if (!cancelled) setValues(loaded);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(loadError instanceof Error ? loadError.message : props.labels.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [props.labels.loadFailed, props.loadValues]);

  function update<K extends keyof SyncSettingsDialogValues>(key: K, value: SyncSettingsDialogValues[K]) {
    setValues((current) => ({ ...current, [key]: value }));
    setMessage("");
    setError("");
  }

  function normalize(next: SyncSettingsDialogValues): SyncSettingsDialogValues {
    const normalized = props.normalizeValues ? props.normalizeValues(next) : next;
    return {
      ...normalized,
      webDavUrl: normalized.webDavUrl.trim(),
      username: normalized.username.trim(),
      folderTitle: normalized.folderTitle?.trim() || "",
      deviceName: normalized.deviceName.trim(),
    };
  }

  function sync(action: SyncSettingsDialogAction, event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const next = normalize(values);
    if (busyAction || !next.webDavUrl.trim()) return;
    setValues(next);
    setBusyAction(action);
    setMessage(props.labels.syncing);
    setError("");
    props.syncValues(next, action)
      .then((result) => {
        if (result?.values) setValues(result.values);
        setMessage(result?.message || "");
        props.onSynced?.();
      })
      .catch((syncError) => {
        setMessage("");
        setError(syncError instanceof Error ? syncError.message : props.labels.syncFailed);
      })
      .finally(() => setBusyAction(null));
  }

  return (
    <div className="sync-settings-scrim" onClick={props.onClose}>
      <form className="sync-settings-dialog" role="dialog" aria-modal="true" aria-label={props.labels.title} onClick={(event) => event.stopPropagation()} onSubmit={(event) => sync("pullRemote", event)}>
        <header className="sync-settings-header">
          <h2 className="sync-settings-title">{props.labels.title}</h2>
          <button className="sync-settings-close" type="button" onClick={props.onClose}>{props.labels.close}</button>
        </header>
        <div className="sync-settings-grid">
          <label className="sync-settings-field full">
            <span className="sync-settings-label">{props.labels.webDavAddress}</span>
            <input className="sync-settings-input" type="url" placeholder="https://example.com/dav" value={values.webDavUrl} onChange={(event) => update("webDavUrl", event.currentTarget.value)} />
          </label>
          <label className="sync-settings-field">
            <span className="sync-settings-label">{props.labels.username}</span>
            <input className="sync-settings-input" type="text" autoComplete="username" value={values.username} onChange={(event) => update("username", event.currentTarget.value)} />
          </label>
          <label className="sync-settings-field">
            <span className="sync-settings-label">{props.labels.password}</span>
            <input className="sync-settings-input" type="password" autoComplete="current-password" value={values.password} onChange={(event) => update("password", event.currentTarget.value)} />
          </label>
          {showFolderTitle && (
            <label className="sync-settings-field">
              <span className="sync-settings-label">{props.labels.folderTitle}</span>
              <input className="sync-settings-input" type="text" value={values.folderTitle || ""} onChange={(event) => update("folderTitle", event.currentTarget.value)} />
            </label>
          )}
        </div>
        <p className="sync-settings-message">{props.labels.help}</p>
        <div className="sync-settings-actions">
          <button className="sync-settings-button" type="submit" disabled={!!busyAction || !values.webDavUrl.trim()}>
            {busyAction === "pullRemote" ? props.labels.syncing : props.labels.useRemote}
          </button>
          <button className="sync-settings-button secondary" type="button" disabled={!!busyAction || !values.webDavUrl.trim()} onClick={() => sync("pushLocal")}>
            {busyAction === "pushLocal" ? props.labels.syncing : props.labels.useLocal}
          </button>
        </div>
        {values.deviceId && <p className="sync-settings-message">{props.labels.deviceId(values.deviceId)}</p>}
        {message && <p className="sync-settings-message">{message}</p>}
        {error && <p className="sync-settings-error">{error}</p>}
      </form>
    </div>
  );
}
