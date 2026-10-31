# :settings Module Core

In-app settings. Depends only on `:base`. `gradle/license-html-generator.gradle` (applied in `:app`) generates `settings/src/main/assets/licenses.html` from app dependencies at build time; manual overrides in `app/licenses.yml`. Rendered by `LicensesPage` in a WebView.

- `ui/page/SettingsPage` (route `/settings`) with SV menu components: `ThemeMenuSV` (light/dark/system radio group + Material You dynamic-colors switch, hidden when `DynamicColors.isDynamicColorAvailable()` is false — unsupported theme or below Android 12), `LogMenuSV`, `LicensesMenuSV`, `VersionMenuSV`
- Sub-pages: `DonationsPage` (route `/donations`, layout `page_donations.xml`), `LicensesPage`, `LogPage`
- `provider/component/SettingsSharedPreferences` (registered via `SettingsProviderModule`) persists two BehaviorSubject-backed settings: `selectedTheme` (AppCompatDelegate MODE_NIGHT_FOLLOW_SYSTEM/NO/YES) and `dynamicColorsEnabled` (default true, `DEFAULT_DYNAMIC_COLORS_ENABLED`); both write through the shared provider executor (commit)

Nothing else here; keep it minimal.
