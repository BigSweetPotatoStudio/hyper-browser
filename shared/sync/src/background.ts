export type SyncBackgroundSignal = {
  stateChanged?: boolean;
  launcherChanged?: boolean;
};

export type SyncBackgroundRemoteCheck<TSyncResult extends SyncBackgroundSignal> = {
  changed: boolean;
  stateChanged: boolean;
  launcherChanged: boolean;
  synced: boolean;
  updatedAt: number;
  syncResult?: TSyncResult;
};

export type SyncBackgroundController<TSyncResult extends SyncBackgroundSignal> = {
  scheduleSync: () => void;
  runFullSyncNow: () => Promise<TSyncResult>;
  checkRemoteChanges: (options?: { notifyPages?: boolean }) => Promise<SyncBackgroundRemoteCheck<TSyncResult>>;
};

export function createSyncBackgroundController<TSyncResult extends SyncBackgroundSignal>(options: {
  debounceMs: number;
  syncNow: () => Promise<TSyncResult>;
  syncIfEnabled: () => Promise<TSyncResult | null>;
  notifyLauncherChanged?: (result: TSyncResult) => void;
  notifyRemoteSynced?: (updatedAt: number, result: TSyncResult) => void;
  onError?: (scope: string, error: unknown) => void;
}): SyncBackgroundController<TSyncResult> {
  let syncTimer: ReturnType<typeof setTimeout> | null = null;
  let syncRunning = false;
  let syncPending = false;
  let remoteCheckRunning = false;

  function scheduleSync(): void {
    if (syncTimer) clearTimeout(syncTimer);
    syncTimer = setTimeout(() => {
      syncTimer = null;
      runQueuedSync().catch((error) => reportError("sync", error));
    }, options.debounceMs);
  }

  async function runQueuedSync(): Promise<void> {
    if (syncRunning) {
      syncPending = true;
      return;
    }
    syncRunning = true;
    try {
      do {
        syncPending = false;
        const result = await options.syncIfEnabled();
        if (result?.launcherChanged) options.notifyLauncherChanged?.(result);
      } while (syncPending);
    } finally {
      syncRunning = false;
    }
  }

  async function runFullSyncNow(): Promise<TSyncResult> {
    if (syncTimer) {
      clearTimeout(syncTimer);
      syncTimer = null;
    }
    syncPending = false;
    const result = await options.syncNow();
    options.notifyRemoteSynced?.(Date.now(), result);
    return result;
  }

  async function checkRemoteChanges(checkOptions: { notifyPages?: boolean } = {}): Promise<SyncBackgroundRemoteCheck<TSyncResult>> {
    if (remoteCheckRunning) return emptyRemoteCheck();
    remoteCheckRunning = true;
    try {
      const result = await options.syncIfEnabled();
      const updatedAt = Date.now();
      if (!result) return emptyRemoteCheck();
      const response = {
        changed: !!result.launcherChanged,
        stateChanged: !!result.stateChanged,
        launcherChanged: !!result.launcherChanged,
        synced: true,
        updatedAt,
        syncResult: result,
      };
      if (response.launcherChanged && checkOptions.notifyPages) {
        options.notifyRemoteSynced?.(updatedAt, result);
      }
      return response;
    } finally {
      remoteCheckRunning = false;
    }
  }

  function reportError(scope: string, error: unknown): void {
    if (options.onError) {
      options.onError(scope, error);
      return;
    }
    console.warn(`Background ${scope} failed.`, error);
  }

  return {
    scheduleSync,
    runFullSyncNow,
    checkRemoteChanges,
  };
}

function emptyRemoteCheck<TSyncResult extends SyncBackgroundSignal>(): SyncBackgroundRemoteCheck<TSyncResult> {
  return {
    changed: false,
    stateChanged: false,
    launcherChanged: false,
    synced: false,
    updatedAt: 0,
  };
}
