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
  deviceName: string;
  help: string;
  sync: string;
  syncing: string;
  loadFailed: string;
  syncFailed: string;
  deviceId: (deviceId: string) => string;
};

export type SyncSettingsDialogResult = {
  values?: SyncSettingsDialogValues;
  message?: string;
};

type Props = {
  labels: SyncSettingsDialogLabels;
  loadValues: () => Promise<SyncSettingsDialogValues>;
  syncValues: (values: SyncSettingsDialogValues) => Promise<SyncSettingsDialogResult | void>;
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
  const [busy, setBusy] = useState(false);
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

  function sync(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const next = normalize(values);
    if (busy || !next.webDavUrl.trim()) return;
    setValues(next);
    setBusy(true);
    setMessage(props.labels.syncing);
    setError("");
    props.syncValues(next)
      .then((result) => {
        if (result?.values) setValues(result.values);
        setMessage(result?.message || "");
        props.onSynced?.();
      })
      .catch((syncError) => {
        setMessage("");
        setError(syncError instanceof Error ? syncError.message : props.labels.syncFailed);
      })
      .finally(() => setBusy(false));
  }

  return (
    <div className="sync-settings-scrim" onClick={props.onClose}>
      <form className="sync-settings-dialog" role="dialog" aria-modal="true" aria-label={props.labels.title} onClick={(event) => event.stopPropagation()} onSubmit={sync}>
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
          <label className="sync-settings-field">
            <span className="sync-settings-label">{props.labels.deviceName}</span>
            <input className="sync-settings-input" type="text" value={values.deviceName} onChange={(event) => update("deviceName", event.currentTarget.value)} />
          </label>
        </div>
        <p className="sync-settings-message">{props.labels.help}</p>
        <div className="sync-settings-actions">
          <button className="sync-settings-button" type="submit" disabled={busy || !values.webDavUrl.trim()}>
            {busy ? props.labels.syncing : props.labels.sync}
          </button>
        </div>
        {values.deviceId && <p className="sync-settings-message">{props.labels.deviceId(values.deviceId)}</p>}
        {message && <p className="sync-settings-message">{message}</p>}
        {error && <p className="sync-settings-error">{error}</p>}
      </form>
    </div>
  );
}
