/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import android.content.Context
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.session.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.app.links.AppLinksUseCases
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.prompts.PromptMiddleware
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.search.ext.toDefaultSearchEngineProvider
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.region.RegionMiddleware
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.SettingsUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.service.location.LocationService
import org.mozilla.focus.components.EngineProvider
import org.mozilla.focus.downloads.DownloadService
import org.mozilla.focus.engine.ClientWrapper
import org.mozilla.focus.engine.LocalizedContentInterceptor
import org.mozilla.focus.engine.SanityCheckMiddleware
import org.mozilla.focus.notification.PrivateNotificationMiddleware
import org.mozilla.focus.search.SearchFilterMiddleware
import org.mozilla.focus.search.SearchMigration
import org.mozilla.focus.state.AppState
import org.mozilla.focus.state.AppStore
import org.mozilla.focus.state.Screen
import org.mozilla.focus.telemetry.TelemetryMiddleware
import org.mozilla.focus.utils.Settings

/**
 * Helper object for lazily initializing components.
 */
class Components(
    context: Context,
    private val engineOverride: Engine? = null,
    private val clientOverride: Client? = null
) {
    val appStore: AppStore by lazy {
        AppStore(AppState(
            screen = determineInitialScreen(context)
        ))
    }

    val engineDefaultSettings by lazy {
        val settings = Settings.getInstance(context)

        DefaultSettings(
                requestInterceptor = LocalizedContentInterceptor(context),
                trackingProtectionPolicy = settings.createTrackingProtectionPolicy(),
                javascriptEnabled = !settings.shouldBlockJavaScript(),
                remoteDebuggingEnabled = settings.shouldEnableRemoteDebugging(),
                webFontsEnabled = !settings.shouldBlockWebFonts()
        )
    }

    val engine: Engine by lazy {
        engineOverride ?: EngineProvider.createEngine(context, engineDefaultSettings).apply {
            Settings.getInstance(context).setupSafeBrowsing(this)
        }
    }

    val client: ClientWrapper by lazy {
        ClientWrapper(clientOverride ?: EngineProvider.createClient(context))
    }

    val trackingProtectionUseCases by lazy { TrackingProtectionUseCases(store, engine) }

    val settingsUseCases by lazy { SettingsUseCases(engine, store) }

    val store by lazy {
        BrowserStore(
            middleware = listOf(
                PrivateNotificationMiddleware(context),
                TelemetryMiddleware(),
                DownloadMiddleware(context, DownloadService::class.java),
                SanityCheckMiddleware(),
                // We are currently using the default location service. We should consider using
                // an actual implementation:
                // https://github.com/mozilla-mobile/focus-android/issues/4781
                RegionMiddleware(context, LocationService.default()),
                SearchMiddleware(context, migration = SearchMigration(context)),
                SearchFilterMiddleware(),
                PromptMiddleware()
            ) + EngineMiddleware.create(engine, ::findSessionById)
        )
    }

    @Suppress("DEPRECATION")
    private fun findSessionById(tabId: String): Session? {
        return sessionManager.findSessionById(tabId)
    }

    /**
     * The [CustomTabsServiceStore] holds global custom tabs related data.
     */
    val customTabsStore by lazy { CustomTabsServiceStore() }

    @Suppress("DEPRECATION")
    val sessionUseCases: SessionUseCases by lazy { SessionUseCases(store, sessionManager) }

    @Suppress("DEPRECATION")
    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store, sessionManager) }

    val searchUseCases: SearchUseCases by lazy {
        SearchUseCases(store, store.toDefaultSearchEngineProvider(), tabsUseCases)
    }

    val contextMenuUseCases: ContextMenuUseCases by lazy { ContextMenuUseCases(store) }

    val downloadsUseCases: DownloadsUseCases by lazy { DownloadsUseCases(store) }

    val appLinksUseCases: AppLinksUseCases by lazy { AppLinksUseCases(context.applicationContext) }

    @Suppress("DEPRECATION")
    val customTabsUseCases: CustomTabsUseCases by lazy { CustomTabsUseCases(sessionManager, sessionUseCases.loadUrl) }

    @Deprecated("Use BrowserStore instead")
    private val sessionManager by lazy {
        SessionManager(engine, store)
    }
}

private fun determineInitialScreen(context: Context): Screen {
    return if (Settings.getInstance(context).shouldShowFirstrun()) {
        Screen.FirstRun
    } else {
        Screen.Home
    }
}