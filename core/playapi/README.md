# core:playapi

Vendored **GPlayApi** — a complete Google Play Store protobuf API client — used by
Nova Store to log into Google Play, search apps, fetch app details, purchase free
apps and obtain APK download URLs (base + split APKs, OBB/patches).

* **Upstream**: open-source GPlayApi (GPL-3.0), package renamed to `com.novastore.playapi`
* **Upstream license**: GPL-3.0 (see `LICENSE` upstream — full text available at
  https://www.gnu.org/licenses/gpl-3.0.txt)
* Because this module contains GPL-3 code, **Nova Store as a whole is licensed
  GPL-3**.

## Layout

```
src/main/java/com/novastore/playapi/**   Kotlin sources (package names kept as upstream)
src/main/proto/GooglePlay.proto        proto2 wire definitions (2126 lines)
src/main/resources/*.properties        device spoofing profiles (checkin configs)
```

## Adaptations made for Nova Store

1. **Plain OkHttp networking** — `network/HttpClient.kt` rewritten from
   Retrofit/OkHttp3 to a single shared OkHttp 4 client (connect 15s / read 30s /
   write 30s). `post(url, headers, params)` now encodes params as a form body
   (`FormBody`), which matches the classic ClientLogin/FDFE behaviour.
   `network/HttpService.kt` (the Retrofit interface) was deleted.
   Public API preserved: `get(url, headers)`, `get(url, headers, params)`,
   `post(url, headers, params)`, `post(url, headers, RequestBody)`,
   `getX(url, headers, paramString)` — all returning `PlayResponse`.
2. **Removed Gson dependency** — `helpers/AppSalesHelper.kt` (scraped the
   third-party bestappsale.com API, not part of Google's API) was removed
   together with its `SALES_URL` constant from `GooglePlayApi`.
3. **Removed** `helpers/ReviewsHelper.kt` (not needed for Nova Store's scope:
   search / details / purchase / top charts / categories).
4. **OkHttp 4 compatibility** — `RequestBody.create(MediaType.parse(...), bytes)`
   replaced with `bytes.toRequestBody("application/x-protobuf".toMediaType())`
   in `GooglePlayApi.kt` and `AppDetailsHelper.kt`.
5. **New: password login** — `GooglePlayApi.login(email, password)` (classic
   ClientLogin, works with App Passwords) and `AuthHelper.login(email, password,
   deviceName)` performing the whole flow (checkin → device config → tokens →
   toc).
6. **New: anonymous login** — `AnonymousAuth.login(dispenserUrl, deviceName)`
   fetches `{"email":"...","token":"..."}` from an anonymous token dispenser
   (tiny regex JSON parser, no Gson) and builds an `AuthData`.
7. **New: device listing** — `DeviceManager.listDevices()` returns all bundled
   device profiles.
8. **New modern device profiles** (modeled on `px_3a.properties`):
   `px_10_pro`, `px_10`, `px_8_pro`, `px_xl` (classic), `sm_s24_ultra`,
   `px_tablet` — see `src/main/resources/`.
9. Kept package names `com.novastore.playapi` as-is (vendored library, GPL-3
   attribution in this file).

## Public API entry points

* `AuthHelper.login(email, password, deviceName = "px_3a.properties")` → `AuthData`
* `AuthHelper.build(email, aasToken[, deviceName|properties])` → `AuthData`
* `AnonymousAuth.login(dispenserUrl, deviceName = "px_3a.properties")` → `AuthData`
* `GooglePlayApi(authData)`: `toc()`, `uploadDeviceConfig()`, `generateGsfId()`,
  `generateAASToken(...)`, `generateToken(aasToken, service)`, `login(email, password)`
* Helpers (singletons via `X.with(authData)`):
  * `SearchHelper`: `searchSuggestions`, `searchSuggestions2`, `searchResults(query, nextPageUrl)`, `next(bundleSet)`
  * `AppDetailsHelper`: `getAppByPackageName(packageName)`, `getAppByPackageName(packageList)`
  * `PurchaseHelper`: `getBuyResponse`, `getDeliveryResponse`, `purchase(packageName, versionCode, offerType)` → `List<File>` (BASE/SPLIT/OBB/PATCH)
  * `TopChartsHelper`: `getCluster(type, chart)`
  * `CategoryHelper`: `getAllCategoriesList(type)`, `getSubCategoryCluster(homeUrl)`
  * `StreamHelper`: `getNavStream(type, category)`, `getEditorChoiceStream(category)`, `next(nextPageUrl)`
  * `ClusterHelper`, `BrowseHelper`, `AuthValidator` — kept as upstream
