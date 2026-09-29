# :settings Module Core

In-app settings. Depends only on `:base`. `gradle/license-html-generator.gradle` (applied in `:app`) generates `settings/src/main/assets/licenses.html` from app dependencies at build time; manual overrides in `app/licenses.yml`. Rendered by `LicensesPage` in a WebView.

- `ui/page/SettingsPage` (route `/settings`) with SV menu components: `ThemeMenuSV`, `LogMenuSV`, `LicensesMenuSV`, `VersionMenuSV`
- Sub-pages: `DonationsPage` (route `/donations`, layout `page_donations.xml`), `LicensesPage`, `LogPage`
- `provider/component/SettingsSharedPreferences` (registered via `SettingsProviderModule`) persists settings

Nothing else here; keep it minimal.
