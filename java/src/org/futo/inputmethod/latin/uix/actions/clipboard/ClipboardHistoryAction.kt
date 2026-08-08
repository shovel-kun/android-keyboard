package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.PersistentStateInitialization
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.PasteAction
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore

val ClipboardHistoryEnabled = SettingsKey(
    booleanPreferencesKey("enableClipboardHistory"),
    true
)

val ClipboardHistorySaveSensitive = SettingsKey(
    booleanPreferencesKey("clipboard_history_save_sensitive"),
    false
)

val ClipboardShowPinnedOnTop = SettingsKey(
    booleanPreferencesKey("clipboard_history_show_pinned_on_top"),
    false
)

val ClipboardSingleColumn = SettingsKey(
    booleanPreferencesKey("clipboard_history_single_column"),
    false
)

val ClipboardQuickClipsEnabled = SettingsKey(
    booleanPreferencesKey("clipboard_quick_clips_enabled"),
    true
)

val ClipboardIncognitoMode = SettingsKey(
    booleanPreferencesKey("clipboard_incognito_mode"),
    false
)

val ClipboardSkipDeleteConfirmation = SettingsKey(
    booleanPreferencesKey("clipboard_skip_delete_confirmation"),
    false
)

val ClipboardSaveImages = SettingsKey(
    booleanPreferencesKey("clipboard_save_images"),
    true
)

val ClipboardSaveScreenshots = SettingsKey(
    booleanPreferencesKey("clipboard_save_screenshots"),
    false
)

val ClipboardLinkPreviewsEnabled = SettingsKey(
    booleanPreferencesKey("clipboard_link_previews_enabled"),
    true
)

val ClipboardLimitDownloadsOnMobileData = SettingsKey(
    booleanPreferencesKey("clipboard_limit_downloads_on_mobile_data"),
    true
)

val ClipboardImageTaggingEnabled = SettingsKey(
    booleanPreferencesKey("clipboard_image_tagging_enabled"),
    true
)

val ClipboardPixivSessionId = SettingsKey(
    stringPreferencesKey("clipboard_pixiv_session_id"),
    ""
)

val ClipboardFanboxSessionId = SettingsKey(
    stringPreferencesKey("clipboard_fanbox_session_id"),
    ""
)

val ClipboardXLinkPasteDomain = SettingsKey(
    stringPreferencesKey("clipboard_x_link_paste_domain"),
    "fixupx.com"
)

val ClipboardPixivLinkPasteDomain = SettingsKey(
    stringPreferencesKey("clipboard_pixiv_link_paste_domain"),
    "www.phixiv.net"
)

val ClipboardMastodonLinkPasteDomain = SettingsKey(
    stringPreferencesKey("clipboard_mastodon_link_paste_domain"),
    "fxmas.to"
)

val ClipboardRedditAccessToken = SettingsKey(
    stringPreferencesKey("clipboard_reddit_access_token"),
    ""
)

val ClipboardEmbedDisplayModeSetting = SettingsKey(
    intPreferencesKey("clipboard_embed_display_mode"),
    ClipboardEmbedDisplayMode.ShowEmbed.storedValue
)

val ClipboardArchiveSortModeSetting = SettingsKey(
    intPreferencesKey("clipboard_archive_sort_mode"),
    ClipboardArchiveSortMode.ClipDate.storedValue
)

val ClipboardArchiveSortDirectionSetting = SettingsKey(
    intPreferencesKey("clipboard_archive_sort_direction"),
    ClipboardArchiveSortDirection.Descending.storedValue
)

internal const val ClipboardArchiveBackfillVersion = 1

val ClipboardArchiveBackfillCompletedVersion = SettingsKey(
    intPreferencesKey("clipboard_archive_backfill_completed_version"),
    0
)

val ClipboardHistoryAction = Action(
    icon = R.drawable.clipboard_manager,
    name = R.string.action_clipboard_manager_title,
    simplePressImpl = null,
    canShowKeyboard = true,
    persistentState = { manager ->
        ClipboardHistoryManager.getInstance(manager.getContext())
    },
    altPressImpl = PasteAction.simplePressImpl,
    persistentStateInitialization = PersistentStateInitialization.OnKeyboardLoad,
    windowImpl = { manager, persistent ->
        val unlocked = !manager.isDeviceLocked()
        val clipboardHistoryManager = persistent as ClipboardHistoryManager
        val clipboardSearchActive = mutableStateOf(false)
        val clipboardSearchText = mutableStateOf("")

        manager.getLifecycleScope().launch {
            clipboardHistoryManager.reconcileClipboardStorage()
        }

        object : ActionWindow() {
            @Composable
            override fun windowName(): String {
                return stringResource(R.string.action_clipboard_manager_title)
            }

            @Composable
            override fun WindowToolbarControls(rowScope: RowScope) {
                with(rowScope) {
                    ClipboardHistoryActionToolbarControls(
                        unlocked = unlocked,
                        clipboardHistoryManager = clipboardHistoryManager
                    )
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                if(!clipboardSearchActive.value) {
                    super.WindowTitleBar(rowScope)
                }
                with(rowScope) {
                    ClipboardHistoryActionTitleBar(
                        clipboardHistoryManager = clipboardHistoryManager,
                        unlocked = unlocked,
                        searchActive = clipboardSearchActive,
                        searchText = clipboardSearchText
                    )
                }
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                ClipboardHistoryActionWindowContents(
                    manager = manager,
                    clipboardHistoryManager = clipboardHistoryManager,
                    unlocked = unlocked,
                    searchText = clipboardSearchText.value
                )
            }
        }
    },
    settingsMenu = UserSettingsMenu(
        title = R.string.action_clipboard_manager_settings_title,
        navPath = "actions/clipboard_history",
        registerNavPath = true,
        settings = listOf(
            userSettingNavigationItem(
                title = R.string.typing_settings_enable_clipboard_history,
                style = org.futo.inputmethod.latin.uix.settings.NavigationItemStyle.Misc,
                navigateTo = "clipboardHistory",
                icon = R.drawable.clipboard_manager
            ),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_show_quick_clips,
                setting = ClipboardQuickClipsEnabled
            ),
            userSettingToggleDataStore(
                title = R.string.typing_settings_enable_clipboard_history,
                setting = ClipboardHistoryEnabled
            ).copy(searchTags = R.string.typing_settings_enable_clipboard_history_tags),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_incognito_mode,
                subtitle = R.string.action_clipboard_manager_settings_incognito_mode_subtitle,
                setting = ClipboardIncognitoMode
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_save_images,
                setting = ClipboardSaveImages
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_save_screenshots,
                subtitle = R.string.action_clipboard_manager_settings_save_screenshots_subtitle,
                component = {
                    val context = LocalContext.current
                    val (enabled, setEnabled) = useDataStore(ClipboardSaveScreenshots)
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        setEnabled(granted)
                    }
                    SettingToggleRaw(
                        title = stringResource(R.string.action_clipboard_manager_settings_save_screenshots),
                        enabled = enabled,
                        setValue = { requested ->
                            if(!requested) {
                                setEnabled(false)
                            } else if(ContextCompat.checkSelfPermission(
                                    context,
                                    ScreenshotHelper.permission
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                setEnabled(true)
                            } else {
                                launcher.launch(ScreenshotHelper.permission)
                            }
                        },
                        subtitle = stringResource(R.string.action_clipboard_manager_settings_save_screenshots_subtitle)
                    )
                },
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_link_previews,
                subtitle = R.string.action_clipboard_manager_settings_link_previews_subtitle,
                setting = ClipboardLinkPreviewsEnabled
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_image_tagging,
                subtitle = R.string.action_clipboard_manager_settings_image_tagging_subtitle,
                setting = ClipboardImageTaggingEnabled
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_x_link_paste_domain,
                subtitle = R.string.action_clipboard_manager_settings_x_link_paste_domain_subtitle,
                component = {
                    val domain = useDataStore(ClipboardXLinkPasteDomain)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_x_link_paste_domain))
                        TextField(
                            value = domain.value,
                            onValueChange = domain.setValue,
                            placeholder = { Text("fixupx.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_pixiv_link_paste_domain,
                subtitle = R.string.action_clipboard_manager_settings_pixiv_link_paste_domain_subtitle,
                component = {
                    val domain = useDataStore(ClipboardPixivLinkPasteDomain)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_pixiv_link_paste_domain))
                        TextField(
                            value = domain.value,
                            onValueChange = domain.setValue,
                            placeholder = { Text("www.phixiv.net") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_mastodon_link_paste_domain,
                subtitle = R.string.action_clipboard_manager_settings_mastodon_link_paste_domain_subtitle,
                component = {
                    val domain = useDataStore(ClipboardMastodonLinkPasteDomain)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_mastodon_link_paste_domain))
                        TextField(
                            value = domain.value,
                            onValueChange = domain.setValue,
                            placeholder = { Text("fxmas.to") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_limit_mobile_data,
                subtitle = R.string.action_clipboard_manager_settings_limit_mobile_data_subtitle,
                setting = ClipboardLimitDownloadsOnMobileData
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_pixiv_session_id,
                subtitle = R.string.action_clipboard_manager_settings_pixiv_session_id_subtitle,
                component = {
                    val sessionId = useDataStore(ClipboardPixivSessionId)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_pixiv_session_id))
                        TextField(
                            value = sessionId.value,
                            onValueChange = sessionId.setValue,
                            placeholder = { Text("PHPSESSID") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = {
                    useDataStoreValue(ClipboardHistoryEnabled) &&
                        useDataStoreValue(ClipboardLinkPreviewsEnabled)
                }
            ),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_fanbox_session_id,
                subtitle = R.string.action_clipboard_manager_settings_fanbox_session_id_subtitle,
                component = {
                    val sessionId = useDataStore(ClipboardFanboxSessionId)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_fanbox_session_id))
                        TextField(
                            value = sessionId.value,
                            onValueChange = sessionId.setValue,
                            placeholder = { Text("FANBOXSESSID") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = {
                    useDataStoreValue(ClipboardHistoryEnabled) &&
                        useDataStoreValue(ClipboardLinkPreviewsEnabled)
                }
            ),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_reddit_access_token,
                subtitle = R.string.action_clipboard_manager_settings_reddit_access_token_subtitle,
                component = {
                    val redditCredential = useDataStore(ClipboardRedditAccessToken)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 8.dp)
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_settings_reddit_access_token))
                        TextField(
                            value = redditCredential.value,
                            onValueChange = redditCredential.setValue,
                            placeholder = { Text("client id or Bearer token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                visibilityCheck = {
                    useDataStoreValue(ClipboardHistoryEnabled) &&
                        useDataStoreValue(ClipboardLinkPreviewsEnabled)
                }
            ),
            UserSetting(
                name = R.string.action_clipboard_manager_settings_save_sensitive_clips,
                subtitle = R.string.action_clipboard_manager_settings_save_sensitive_clips_subtitle,
                component = {
                    SettingToggleDataStore(
                        title = stringResource(R.string.action_clipboard_manager_settings_save_sensitive_clips),
                        subtitle = stringResource(R.string.action_clipboard_manager_settings_save_sensitive_clips_subtitle),
                        setting = ClipboardHistorySaveSensitive
                    )
                },
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_show_pinned_above_others,
                setting = ClipboardShowPinnedOnTop
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_list_layout,
                setting = ClipboardSingleColumn
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_skip_delete_confirmation,
                setting = ClipboardSkipDeleteConfirmation
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
            userSettingNavigationItem(
                title = R.string.action_clipboard_manager_settings_export_clipboard,
                subtitle = R.string.action_clipboard_manager_settings_export_clipboard_subtitle,
                style = org.futo.inputmethod.latin.uix.settings.NavigationItemStyle.Misc,
                navigateTo = "exportingClipboard"
            ).copy(
                searchTags = R.string.settings_import_export_tags,
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
            userSettingNavigationItem(
                title = R.string.action_clipboard_manager_settings_import_clipboard,
                subtitle = R.string.action_clipboard_manager_settings_import_clipboard_subtitle,
                style = org.futo.inputmethod.latin.uix.settings.NavigationItemStyle.Misc,
                navigate = { nav ->
                    SettingsExporter.triggerImportClipboardBackup(nav.context)
                }
            ).copy(
                searchTags = R.string.settings_import_export_tags,
                visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }
            ),
        )
    )
)
