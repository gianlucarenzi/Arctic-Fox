/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.goanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.goanna.gfx.LayerView;
import org.mozilla.goanna.mozglue.GoannaLoader;
import org.mozilla.goanna.util.Clipboard;
import org.mozilla.goanna.util.EventCallback;
import org.mozilla.goanna.util.GoannaEventListener;
import org.mozilla.goanna.util.HardwareUtils;
import org.mozilla.goanna.util.NativeEventListener;
import org.mozilla.goanna.util.NativeJSObject;
import org.mozilla.goanna.util.ThreadUtils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

public class GoannaView extends LayerView
    implements ContextGetter {

    private static final String DEFAULT_SHARED_PREFERENCES_FILE = "GoannaView";
    private static final String LOGTAG = "GoannaView";

    private ChromeDelegate mChromeDelegate;
    private ContentDelegate mContentDelegate;

    private final GoannaEventListener mGoannaEventListener = new GoannaEventListener() {
        @Override
        public void handleMessage(final String event, final JSONObject message) {
            ThreadUtils.postToUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (event.equals("Goanna:Ready")) {
                            handleReady(message);
                        } else if (event.equals("Content:StateChange")) {
                            handleStateChange(message);
                        } else if (event.equals("Content:LoadError")) {
                            handleLoadError(message);
                        } else if (event.equals("Content:PageShow")) {
                            handlePageShow(message);
                        } else if (event.equals("DOMTitleChanged")) {
                            handleTitleChanged(message);
                        } else if (event.equals("Link:Favicon")) {
                            handleLinkFavicon(message);
                        } else if (event.equals("Prompt:Show") || event.equals("Prompt:ShowTop")) {
                            handlePrompt(message);
                        } else if (event.equals("Accessibility:Event")) {
                            int mode = getImportantForAccessibility();
                            if (mode == View.IMPORTANT_FOR_ACCESSIBILITY_YES ||
                                mode == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                                GoannaAccessibility.sendAccessibilityEvent(message);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(LOGTAG, "handleMessage threw for " + event, e);
                    }
                }
            });
        }
    };

    private final NativeEventListener mNativeEventListener = new NativeEventListener() {
        @Override
        public void handleMessage(final String event, final NativeJSObject message, final EventCallback callback) {
            try {
                if ("Accessibility:Ready".equals(event)) {
                    GoannaAccessibility.updateAccessibilitySettings(getContext());
                } else if ("GoannaView:Message".equals(event)) {
                    // We need to pull out the bundle while on the Goanna thread.
                    NativeJSObject json = message.optObject("data", null);
                    if (json == null) {
                        // Must have payload to call the message handler.
                        return;
                    }
                    final Bundle data = json.toBundle();
                    ThreadUtils.postToUiThread(new Runnable() {
                        @Override
                        public void run() {
                            handleScriptMessage(data, callback);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(LOGTAG, "handleMessage threw for " + event, e);
            }
        }
    };

    public GoannaView(Context context) {
        super(context);
        init(context, null, true);
    }

    public GoannaView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GoannaView);
        String url = a.getString(R.styleable.GoannaView_url);
        boolean doInit = a.getBoolean(R.styleable.GoannaView_doinit, true);
        a.recycle();
        init(context, url, doInit);
    }

    private void init(Context context, String url, boolean doInit) {
        // Perform common initialization for Fennec/GoannaView.
        GoannaAppShell.setLayerView(this);

        // TODO: Fennec currently takes care of its own initialization, so this
        // flag is a hack used in Fennec to prevent GoannaView initialization.
        // This should go away once Fennec also uses GoannaView for
        // initialization.
        if (!doInit)
            return;

        // If running outside of a GoannaActivity (eg, from a library project),
        // load the native code and disable content providers
        boolean isGoannaActivity = false;
        try {
            isGoannaActivity = context instanceof GoannaActivity;
        } catch (NoClassDefFoundError ex) {}

        if (!isGoannaActivity) {
            // Set the GoannaInterface if the context is an activity and the GoannaInterface
            // has not already been set
            if (context instanceof Activity && getGoannaInterface() == null) {
                setGoannaInterface(new BaseGoannaInterface(context));
            }

            Clipboard.init(context);
            HardwareUtils.init(context);

            // If you want to use GoannaNetworkManager, start it.

            GoannaLoader.loadMozGlue(context);

            final GoannaProfile profile = GoannaProfile.get(context);
         }

        if (url != null) {
            GoannaThread.setUri(url);
            GoannaThread.setAction(Intent.ACTION_VIEW);
            GoannaAppShell.sendEventToGoanna(GoannaEvent.createURILoadEvent(url));
        }
        GoannaAppShell.setContextGetter(this);
        if (context instanceof Activity) {
            Tabs tabs = Tabs.getInstance();
            tabs.attachToContext(context);
        }

        EventDispatcher.getInstance().registerGoannaThreadListener(mGoannaEventListener,
            "Goanna:Ready",
            "Accessibility:Event",
            "Content:StateChange",
            "Content:LoadError",
            "Content:PageShow",
            "DOMTitleChanged",
            "Link:Favicon",
            "Prompt:Show",
            "Prompt:ShowTop");

        EventDispatcher.getInstance().registerGoannaThreadListener(mNativeEventListener,
            "Accessibility:Ready",
            "GoannaView:Message");

        initializeView(EventDispatcher.getInstance());

        if (GoannaThread.checkAndSetLaunchState(GoannaThread.LaunchState.Launching, GoannaThread.LaunchState.Launched)) {
            // This is the first launch, so finish initialization and go.
            GoannaProfile profile = GoannaProfile.get(context).forceCreate();

            GoannaAppShell.sendEventToGoanna(GoannaEvent.createObjectEvent(
                GoannaEvent.ACTION_OBJECT_LAYER_CLIENT, getLayerClientObject()));
            GoannaThread.createAndStart();
        } else if(GoannaThread.checkLaunchState(GoannaThread.LaunchState.GoannaRunning)) {
            // If Goanna is already running, that means the Activity was
            // destroyed, so we need to re-attach Goanna to this GoannaView.
            connectToGoanna();
        }
    }

    /**
    * Add a Browser to the GoannaView container.
    * @param url The URL resource to load into the new Browser.
    */
    public Browser addBrowser(String url) {
        Tab tab = Tabs.getInstance().loadUrl(url, Tabs.LOADURL_NEW_TAB);
        if (tab != null) {
            return new Browser(tab.getId());
        }
        return null;
    }

    /**
    * Remove a Browser from the GoannaView container.
    * @param browser The Browser to remove.
    */
    public void removeBrowser(Browser browser) {
        Tab tab = Tabs.getInstance().getTab(browser.getId());
        if (tab != null) {
            Tabs.getInstance().closeTab(tab);
        }
    }

    /**
    * Set the active/visible Browser.
    * @param browser The Browser to make selected.
    */
    public void setCurrentBrowser(Browser browser) {
        Tab tab = Tabs.getInstance().getTab(browser.getId());
        if (tab != null) {
            Tabs.getInstance().selectTab(tab.getId());
        }
    }

    /**
    * Get the active/visible Browser.
    * @return The current selected Browser.
    */
    public Browser getCurrentBrowser() {
        Tab tab = Tabs.getInstance().getSelectedTab();
        if (tab != null) {
            return new Browser(tab.getId());
        }
        return null;
    }

    /**
    * Get the list of current Browsers in the GoannaView container.
    * @return An unmodifiable List of Browser objects.
    */
    public List<Browser> getBrowsers() {
        ArrayList<Browser> browsers = new ArrayList<Browser>();
        Iterable<Tab> tabs = Tabs.getInstance().getTabsInOrder();
        for (Tab tab : tabs) {
            browsers.add(new Browser(tab.getId()));
        }
        return Collections.unmodifiableList(browsers);
    }

    public void importScript(final String url) {
        if (url.startsWith("resource://android/assets/")) {
            GoannaAppShell.sendEventToGoanna(GoannaEvent.createBroadcastEvent("GoannaView:ImportScript", url));
            return;
        }

        throw new IllegalArgumentException("Must import script from 'resources://android/assets/' location.");
    }

    private void connectToGoanna() {
        GoannaThread.setLaunchState(GoannaThread.LaunchState.GoannaRunning);
        Tab selectedTab = Tabs.getInstance().getSelectedTab();
        if (selectedTab != null)
            Tabs.getInstance().notifyListeners(selectedTab, Tabs.TabEvents.SELECTED);
        goannaConnected();
        GoannaAppShell.sendEventToGoanna(GoannaEvent.createBroadcastEvent("Viewport:Flush", null));
    }

    private void handleReady(final JSONObject message) {
        connectToGoanna();

        if (mChromeDelegate != null) {
            mChromeDelegate.onReady(this);
        }
    }

    private void handleStateChange(final JSONObject message) throws JSONException {
        int state = message.getInt("state");
        if ((state & GoannaAppShell.WPL_STATE_IS_NETWORK) != 0) {
            if ((state & GoannaAppShell.WPL_STATE_START) != 0) {
                if (mContentDelegate != null) {
                    int id = message.getInt("tabID");
                    mContentDelegate.onPageStart(this, new Browser(id), message.getString("uri"));
                }
            } else if ((state & GoannaAppShell.WPL_STATE_STOP) != 0) {
                if (mContentDelegate != null) {
                    int id = message.getInt("tabID");
                    mContentDelegate.onPageStop(this, new Browser(id), message.getBoolean("success"));
                }
            }
        }
    }

    private void handleLoadError(final JSONObject message) throws JSONException {
        if (mContentDelegate != null) {
            int id = message.getInt("tabID");
            mContentDelegate.onPageStop(GoannaView.this, new Browser(id), false);
        }
    }

    private void handlePageShow(final JSONObject message) throws JSONException {
        if (mContentDelegate != null) {
            int id = message.getInt("tabID");
            mContentDelegate.onPageShow(GoannaView.this, new Browser(id));
        }
    }

    private void handleTitleChanged(final JSONObject message) throws JSONException {
        if (mContentDelegate != null) {
            int id = message.getInt("tabID");
            mContentDelegate.onReceivedTitle(GoannaView.this, new Browser(id), message.getString("title"));
        }
    }

    private void handleLinkFavicon(final JSONObject message) throws JSONException {
        if (mContentDelegate != null) {
            int id = message.getInt("tabID");
            mContentDelegate.onReceivedFavicon(GoannaView.this, new Browser(id), message.getString("href"), message.getInt("size"));
        }
    }

    private void handlePrompt(final JSONObject message) throws JSONException {
        if (mChromeDelegate != null) {
            String hint = message.optString("hint");
            if ("alert".equals(hint)) {
                String text = message.optString("text");
                mChromeDelegate.onAlert(GoannaView.this, null, text, new PromptResult(message));
            } else if ("confirm".equals(hint)) {
                String text = message.optString("text");
                mChromeDelegate.onConfirm(GoannaView.this, null, text, new PromptResult(message));
            } else if ("prompt".equals(hint)) {
                String text = message.optString("text");
                String defaultValue = message.optString("textbox0");
                mChromeDelegate.onPrompt(GoannaView.this, null, text, defaultValue, new PromptResult(message));
            } else if ("remotedebug".equals(hint)) {
                mChromeDelegate.onDebugRequest(GoannaView.this, new PromptResult(message));
            }
        }
    }

    private void handleScriptMessage(final Bundle data, final EventCallback callback) {
        if (mChromeDelegate != null) {
            MessageResult result = null;
            if (callback != null) {
                result = new MessageResult(callback);
            }
            mChromeDelegate.onScriptMessage(GoannaView.this, data, result);
        }
    }

    /**
    * Set the chrome callback handler.
    * This will replace the current handler.
    * @param chrome An implementation of GoannaViewChrome.
    */
    public void setChromeDelegate(ChromeDelegate chrome) {
        mChromeDelegate = chrome;
    }

    /**
    * Set the content callback handler.
    * This will replace the current handler.
    * @param content An implementation of ContentDelegate.
    */
    public void setContentDelegate(ContentDelegate content) {
        mContentDelegate = content;
    }

    public static void setGoannaInterface(final BaseGoannaInterface goannaInterface) {
        GoannaAppShell.setGoannaInterface(goannaInterface);
    }

    public static GoannaAppShell.GoannaInterface getGoannaInterface() {
        return GoannaAppShell.getGoannaInterface();
    }

    protected String getSharedPreferencesFile() {
        return DEFAULT_SHARED_PREFERENCES_FILE;
    }

    @Override
    public SharedPreferences getSharedPreferences() {
        return getContext().getSharedPreferences(getSharedPreferencesFile(), 0);
    }

    /**
    * Wrapper for a browser in the GoannaView container. Associated with a browser
    * element in the Goanna system.
    */
    public class Browser {
        private final int mId;
        private Browser(int Id) {
            mId = Id;
        }

        /**
        * Get the ID of the Browser. This is the same ID used by Goanna for it's underlying
        * browser element.
        * @return The integer ID of the Browser.
        */
        private int getId() {
            return mId;
        }

        /**
        * Load a URL resource into the Browser.
        * @param url The URL string.
        */
        public void loadUrl(String url) {
            JSONObject args = new JSONObject();
            try {
                args.put("url", url);
                args.put("parentId", -1);
                args.put("newTab", false);
                args.put("tabID", mId);
            } catch (Exception e) {
                Log.w(LOGTAG, "Error building JSON arguments for loadUrl.", e);
            }
            GoannaAppShell.sendEventToGoanna(GoannaEvent.createBroadcastEvent("Tab:Load", args.toString()));
        }

        /**
        * Reload the current URL resource into the Browser. The URL is force loaded from the
        * network and is not pulled from cache.
        */
        public void reload() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                tab.doReload();
            }
        }

        /**
        * Stop the current loading operation.
        */
        public void stop() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                tab.doStop();
            }
        }

        /**
        * Check to see if the Browser has session history and can go back to a
        * previous page.
        * @return A boolean flag indicating if previous session exists.
        * This method will likely be removed and replaced by a callback in GoannaViewContent
        */
        public boolean canGoBack() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                return tab.canDoBack();
            }
            return false;
        }

        /**
        * Move backward in the session history, if that's possible.
        */
        public void goBack() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                tab.doBack();
            }
        }

        /**
        * Check to see if the Browser has session history and can go forward to a
        * new page.
        * @return A boolean flag indicating if forward session exists.
        * This method will likely be removed and replaced by a callback in GoannaViewContent
        */
        public boolean canGoForward() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                return tab.canDoForward();
            }
            return false;
        }

        /**
        * Move forward in the session history, if that's possible.
        */
        public void goForward() {
            Tab tab = Tabs.getInstance().getTab(mId);
            if (tab != null) {
                tab.doForward();
            }
        }
    }

    /* Provides a means for the client to indicate whether a JavaScript
     * dialog request should proceed. An instance of this class is passed to
     * various GoannaViewChrome callback actions.
     */
    public class PromptResult {
        private final int RESULT_OK = 0;
        private final int RESULT_CANCEL = 1;

        private final JSONObject mMessage;

        public PromptResult(JSONObject message) {
            mMessage = message;
        }

        private JSONObject makeResult(int resultCode) {
            JSONObject result = new JSONObject();
            try {
                result.put("button", resultCode);
            } catch(JSONException ex) { }
            return result;
        }

        /**
        * Handle a confirmation response from the user.
        */
        public void confirm() {
            JSONObject result = makeResult(RESULT_OK);
            EventDispatcher.sendResponse(mMessage, result);
        }

        /**
        * Handle a confirmation response from the user.
        * @param value String value to return to the browser context.
        */
        public void confirmWithValue(String value) {
            JSONObject result = makeResult(RESULT_OK);
            try {
                result.put("textbox0", value);
            } catch(JSONException ex) { }
            EventDispatcher.sendResponse(mMessage, result);
        }

        /**
        * Handle a cancellation response from the user.
        */
        public void cancel() {
            JSONObject result = makeResult(RESULT_CANCEL);
            EventDispatcher.sendResponse(mMessage, result);
        }
    }

    /* Provides a means for the client to respond to a script message with some data.
     * An instance of this class is passed to GoannaViewChrome.onScriptMessage.
     */
    public class MessageResult {
        private final EventCallback mCallback;

        public MessageResult(EventCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("EventCallback should not be null.");
            }
            mCallback = callback;
        }

        private JSONObject bundleToJSON(Bundle data) {
            JSONObject result = new JSONObject();
            if (data == null) {
                return result;
            }

            final Set<String> keys = data.keySet();
            for (String key : keys) {
                try {
                    result.put(key, data.get(key));
                } catch (JSONException e) {
                }
            }
            return result;
        }

        /**
        * Handle a successful response to a script message.
        * @param value Bundle value to return to the script context.
        */
        public void success(Bundle data) {
            mCallback.sendSuccess(bundleToJSON(data));
        }

        /**
        * Handle a failure response to a script message.
        */
        public void failure(Bundle data) {
            mCallback.sendError(bundleToJSON(data));
        }
    }

    public interface ChromeDelegate {
        /**
        * Tell the host application that Goanna is ready to handle requests.
        * @param view The GoannaView that initiated the callback.
        */
        public void onReady(GoannaView view);

        /**
        * Tell the host application to display an alert dialog.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is loading the content.
        * @param message The string to display in the dialog.
        * @param result A PromptResult used to send back the result without blocking.
        * Defaults to cancel requests.
        */
        public void onAlert(GoannaView view, GoannaView.Browser browser, String message, GoannaView.PromptResult result);
    
        /**
        * Tell the host application to display a confirmation dialog.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is loading the content.
        * @param message The string to display in the dialog.
        * @param result A PromptResult used to send back the result without blocking.
        * Defaults to cancel requests.
        */
        public void onConfirm(GoannaView view, GoannaView.Browser browser, String message, GoannaView.PromptResult result);
    
        /**
        * Tell the host application to display an input prompt dialog.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is loading the content.
        * @param message The string to display in the dialog.
        * @param defaultValue The string to use as default input.
        * @param result A PromptResult used to send back the result without blocking.
        * Defaults to cancel requests.
        */
        public void onPrompt(GoannaView view, GoannaView.Browser browser, String message, String defaultValue, GoannaView.PromptResult result);
    
        /**
        * Tell the host application to display a remote debugging request dialog.
        * @param view The GoannaView that initiated the callback.
        * @param result A PromptResult used to send back the result without blocking.
        * Defaults to cancel requests.
        */
        public void onDebugRequest(GoannaView view, GoannaView.PromptResult result);

        /**
        * Receive a message from an imported script.
        * @param view The GoannaView that initiated the callback.
        * @param data Bundle of data sent with the message. Never null.
        * @param result A MessageResult used to send back a response without blocking. Can be null.
        * Defaults to do nothing.
        */
        public void onScriptMessage(GoannaView view, Bundle data, GoannaView.MessageResult result);
    }

    public interface ContentDelegate {
        /**
        * A Browser has started loading content from the network.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is loading the content.
        * @param url The resource being loaded.
        */
        public void onPageStart(GoannaView view, GoannaView.Browser browser, String url);
    
        /**
        * A Browser has finished loading content from the network.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that was loading the content.
        * @param success Whether the page loaded successfully or an error occurred.
        */
        public void onPageStop(GoannaView view, GoannaView.Browser browser, boolean success);

        /**
        * A Browser is displaying content. This page could have been loaded via
        * network or from the session history.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is showing the content.
        */
        public void onPageShow(GoannaView view, GoannaView.Browser browser);

        /**
        * A page title was discovered in the content or updated after the content
        * loaded.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is showing the content.
        * @param title The title sent from the content.
        */
        public void onReceivedTitle(GoannaView view, GoannaView.Browser browser, String title);

        /**
        * A link element was discovered in the content or updated after the content
        * loaded that specifies a favicon.
        * @param view The GoannaView that initiated the callback.
        * @param browser The Browser that is showing the content.
        * @param url The href of the link element specifying the favicon.
        * @param size The maximum size specified for the favicon, or -1 for any size.
        */
        public void onReceivedFavicon(GoannaView view, GoannaView.Browser browser, String url, int size);
    }

}
