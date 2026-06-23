import { browser } from "wxt/browser";

export type CommandResponse<T> = {
  ok: boolean;
  data?: T;
  error?: string;
};

export function sendCommand<T>(type: string, payload?: unknown): Promise<T> {
  return browser.runtime.sendMessage({ type, payload })
    .then((response: CommandResponse<T>) => {
      if (!response?.ok) {
        throw new Error(response?.error || "Command failed.");
      }
      return response.data as T;
    });
}
