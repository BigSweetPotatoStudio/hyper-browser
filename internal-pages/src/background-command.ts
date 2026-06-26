export const BACKGROUND_TARGET = "hyper.internal.background";

export function sendBackgroundCommand<T = unknown>(type: string, payload?: unknown): Promise<T> {
  const sendMessage = browser?.runtime?.sendMessage;
  if (!sendMessage) return Promise.reject(new Error("Hyper background unavailable."));
  return sendMessage({ target: BACKGROUND_TARGET, type, payload })
    .then((response) => {
      if (!isPlainObject(response) || response.ok !== true) {
        throw new Error(isPlainObject(response) && typeof response.error === "string"
          ? response.error
          : "Hyper background request failed.");
      }
      return response.data as T;
    });
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
