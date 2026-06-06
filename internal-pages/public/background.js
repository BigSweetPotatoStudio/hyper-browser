browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || message.nativeApp !== "hyperBrowser") {
    return Promise.resolve(JSON.stringify({ ok: false, error: "Invalid Hyper bridge message." }));
  }
  const payload = message.payload || {};
  return browser.runtime.sendNativeMessage("hyperBrowser", {
    type: message.type,
    payload: {
      ...payload,
      sourceUrl: payload.sourceUrl || (sender && sender.url) || ""
    }
  });
});
