/* MPL-2.0. Internal built-in routing only; no caller-supplied code or profile data. */
this.abwrRouting = class extends ExtensionAPI {
  getAPI(context) {
    return {
      abwrRouting: {
        async contextForTab(tabId) {
          // GeckoViewTab.userContextId is the actual unsafeSessionContextId.
          // The public cookieStoreId deliberately hides this in private windows.
          const tab = context.extension.tabManager.get(tabId);
          const id = tab.nativeTab.userContextId;
          if (typeof id !== 'string' || !id || id.length > 160) {
            throw new Error('Tab context unavailable');
          }
          return id;
        }
      }
    };
  }
};
