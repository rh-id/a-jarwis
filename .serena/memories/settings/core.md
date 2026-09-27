# :settings Module Core

In-app settings. Depends only on `:base`. Plugin `com.cookpad.android.plugin.license-tools` 1.2.8 generates license info for LicensesPage.

- `ui/page/SettingsPage` (route `/settings`) with SV menu components: `ThemeMenuSV`, `LogMenuSV`, `LicensesMenuSV`, `VersionMenuSV`
- Sub-pages: `LicensesPage`, `LogPage`
- `provider/component/SettingsSharedPreferences` (registered via `SettingsProviderModule`) persists settings

Nothing else here; keep it minimal.
