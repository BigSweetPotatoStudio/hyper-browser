export type CommandResponse<T> = {
  ok: boolean;
  data?: T;
  error?: string;
};

export function sendCommand<T>(type: string, payload?: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage({ type, payload }, (response: CommandResponse<T>) => {
      const error = chrome.runtime.lastError;
      if (error) {
        reject(new Error(error.message));
        return;
      }
      if (!response?.ok) {
        reject(new Error(response?.error || "Command failed."));
        return;
      }
      resolve(response.data as T);
    });
  });
}
