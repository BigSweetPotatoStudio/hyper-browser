browser.runtime.onMessage.addListener((message) => {
  if (!message || message.nativeApp !== "hyperBrowser") {
    return Promise.resolve(JSON.stringify({ ok: false, error: "Invalid Hyper bridge message." }));
  }
  return browser.runtime.sendNativeMessage("hyperBrowser", {
    type: message.type,
    payload: message.payload || {}
  });
});
