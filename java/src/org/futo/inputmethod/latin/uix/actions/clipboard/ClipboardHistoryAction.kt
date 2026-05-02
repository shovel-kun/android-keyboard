package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.PersistentStateInitialization
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.PasteAction
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
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

val ClipboardLinkPreviewsEnabled = SettingsKey(
    booleanPreferencesKey("clipboard_link_previews_enabled"),
    true
)

val ClipboardEmbedDisplayModeSetting = SettingsKey(
    intPreferencesKey("clipboard_embed_display_mode"),
    ClipboardEmbedDisplayMode.ShowEmbed.storedValue
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
        ClipboardHistoryManager(manager.getContext(), manager.getLifecycleScope())
    },
    altPressImpl = PasteAction.simplePressImpl,
    persistentStateInitialization = PersistentStateInitialization.OnKeyboardLoad,
    windowImpl = { manager, persistent ->
        val unlocked = !manager.isDeviceLocked()
        val clipboardHistoryManager = persistent as ClipboardHistoryManager

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
                super.WindowTitleBar(rowScope)
                with(rowScope) {
                    ClipboardHistoryActionTitleBar(
                        manager = manager,
                        clipboardHistoryManager = clipboardHistoryManager,
                        unlocked = unlocked
                    )
                }
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                ClipboardHistoryActionWindowContents(
                    manager = manager,
                    clipboardHistoryManager = clipboardHistoryManager,
                    unlocked = unlocked
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
            userSettingToggleDataStore(
                title = R.string.action_clipboard_manager_settings_link_previews,
                subtitle = R.string.action_clipboard_manager_settings_link_previews_subtitle,
                setting = ClipboardLinkPreviewsEnabled
            ).copy(visibilityCheck = { useDataStoreValue(ClipboardHistoryEnabled) }),
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
