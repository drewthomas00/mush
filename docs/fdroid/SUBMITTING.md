# Submitting Mush to F-Droid

1. Make a GitLab account and fork https://gitlab.com/fdroid/fdroiddata.
2. In the fork, add `metadata/io.github.drewthomas00.mush.yml` from `io.github.drewthomas00.mush.yml`
   here.
3. Open a merge request titled "New app: Mush". Mention:
   - It uses an AccessibilityService only to detect which app is open and full-screen upright
     video (view ids, classes and bounds); nothing is sent off the device unless the user
     configures their own server.
   - It fetches `rules/android.json` from this repo's GitHub (detection rules) and can talk to a
     user-configured, self-hosted Mush server. No other network use.
4. F-Droid's bot builds it from the `v1.0.0` tag; reviewers may ask for changes in the MR.

Store text, icon and changelogs come from `fastlane/metadata/android/` in this repo, so they
update with each release. For a new release: bump `versionCode`/`versionName` in
`android/app/build.gradle.kts`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
and push the tag `v<versionName>`; F-Droid picks it up (UpdateCheckMode: Tags).
