# Publishing Lyne ADAS on Google Play

What to fill in on Play Console, in order. Answers below match what the app actually does (checked
against the merged release manifest: no `INTERNET` permission, no ads or analytics SDKs).

## 1. Developer account

1. Sign up at <https://play.google.com/console/signup> (one-time US$25 fee).
2. Choose **Personal** (or **Organisation** if you have a registered business; that needs a D-U-N-S number).
3. Complete identity verification (government ID) and verify a phone number and email for the account.
4. **Personal accounts created after November 2023:** before the app can go to Production you must run a
   **closed test with at least 12 testers who stay opted in for 14 days in a row**. Plan for this:
   add 12+ Gmail addresses as testers, send them the opt-in link, and keep them installed for two weeks.

## 2. Create the app

- App name: **Lyne ADAS**
- Default language: English (United States) or English (India)
- App or game: **App** · Free or paid: **Free**
- Declarations: Developer Program Policies ✓, US export laws ✓

## 3. Upload

- Build: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
- Use **Play App Signing** (default). Your local keystore (`~/.android/lyne-release.jks`) becomes the
  *upload key*. Keep it and `keystore.properties` backed up outside this laptop.
- Target API level: 36 (Android 16) ✓ · Min SDK 26 (Android 8.0)

## 4. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://sherin-sef-ai.github.io/Lyne-ADAS/privacy.html` |
| Ads | **No**, the app does not contain ads |
| App access | **All functionality is available without special access** (no login) |
| Content rating | Questionnaire category **Utility, Productivity, Communication or Other**; answer **No** to every content question (no violence, sexual content, gambling, user interaction, sharing location with other users, purchases). Expected rating: Everyone / 3+ |
| Target audience | **18 and over** only (it is a driving aid). Not designed for children |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | See section 5 |
| Government app | No |
| Financial features | None |
| Health | Not a health app |

## 5. Data safety form

Google counts data as *collected* only when it is transmitted off the device. Lyne processes
everything on the phone and has no internet permission, so:

- **Does your app collect or share any of the required user data types?** → **No**
- This produces the store label **"No data collected · No data shared"**.
- Encryption in transit / deletion request questions are skipped when no data is collected.

Notes in case a reviewer asks:
- Location comes from Google Play services' fused location provider on the device; Lyne uses it
  only to compute speed and to draw the trip route stored locally.
- Camera frames are analysed in memory. Optional dashcam clips stay in the app's own folder and leave
  the phone only if the user shares one themselves via the Android share sheet.

## 6. Permissions declarations

No special declarations are needed: Lyne uses foreground location only (no background location),
no foreground service, no SMS/call log, no "all files access", no exact alarms.

## 7. Store listing

- **Category:** Auto & Vehicles · **Tags:** Driving, Navigation, Safety
- **Contact email:** required by Play (shown publicly). Use a dedicated address if you prefer.
- **Website:** <https://sherin-sef-ai.github.io/Lyne-ADAS/>

**Short description (≤80 chars)**

> Driver-assist warnings from your phone's camera. On-device, private, offline.

**Full description (≤4000 chars)**

> Lyne turns your Android phone into a second pair of eyes on the road. Mount it on the windshield and
> it watches the traffic ahead, warning you with sound and a clear on-screen alert when something needs
> your attention.
>
> WARNINGS
> • Forward collision: vehicles you're closing on too fast, earlier when brake lights come on
> • Pedestrians, riders and animals in or stepping into your path
> • Tailgating: too little time gap to the car ahead
> • Lane departure and speed-limit reminders
>
> BUILT FOR INDIAN ROADS
> Trained on Indian traffic: auto-rickshaws, two-wheelers with riders, buses, trucks and street animals.
>
> PRIVATE BY DESIGN
> Everything runs on your phone. No account, no cloud, no ads, and no internet permission: nothing
> about you or your drives ever leaves the device.
>
> MORE
> • Trip history with route, distance and the warnings you got
> • Optional event dashcam that saves a clip around critical warnings
> • Day and night display, voice alerts, adjustable sensitivity
> • Reminder to start Lyne when your car's Bluetooth connects
>
> IMPORTANT
> Lyne is a warning-only driver aid. It never brakes or steers, is not a certified safety system, and
> can miss hazards or warn late, especially at night, in rain or glare. Always keep your eyes on the
> road; you are responsible for driving safely. Mount the phone where it does not block your view and
> follow local laws.

**Graphics**

| Asset | Size | Source |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit | `docs/img/logo/` (export the mark at 512) |
| Feature graphic | 1024 × 500 PNG/JPG | Logo + a road-scene screenshot |
| Phone screenshots | 2–8, 16:9 or 9:16, min 320 px side | Driving screen with a warning, onboarding, trip history, settings |

## 8. Release

1. Testing → **Closed testing** → create a track, upload the AAB, add the 12+ testers, roll out.
2. After 14 days with 12+ opted-in testers, apply for **Production access** (Dashboard).
3. Production → create release → upload the AAB (bump `versionCode` for each upload) → roll out.

## Before each upload

- [ ] `versionCode` bumped in `app/build.gradle.kts`
- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] Real-drive check with Location on
- [ ] Release notes written
