# CookieChecker

Bulk Facebook cookie (datr / c_user / xs) LIVE-or-DEAD validator for Android.

- **MVVM**: `CookieUiState` + `CookieViewModel` + `CookieRepository` + `CookieCheckerService`
- **Network**: OkHttp, mobile UA, `Cookie` header per item, redirect + body heuristics
- **Concurrency**: coroutines + `Semaphore(1..8)`, adjustable delay, Pause / Resume / Stop
- **Import**: paste text, or SAF file picker for `.txt` / `.csv` / `.xlsx`
  (XLSX parsed with stdlib ZIP+XML — no POI, keeps APK small)
- **UI**: Compose Material 3 dashboard, progress, [All/Live/Dead] filters, copy + CSV export

## Build

Push to GitHub → Actions workflow `.github/workflows/build.yml` builds the debug APK.
Download it from the workflow run's **Artifacts** (`CookieChecker-debug`).
