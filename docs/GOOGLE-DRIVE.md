# Google Drive: setting up the sign-in (once, by the owner of the app)

The app signs in to Google with an **Android OAuth client**. This client contains only the package name and the fingerprint of the release key. **No secret goes into the code or the repository.** Without the client, everything else works: files, links and backup to a file.

1. **Project:** open https://console.cloud.google.com → choose or create a project (e.g. «Billable Calls»).
2. **Drive API:** go to *APIs & Services → Library* → «Google Drive API» → **Enable**.
3. **Consent screen:** go to *Google Auth Platform*.
   - **Branding:** set the app name «Billable Calls», your support email, and the developer email.
   - **Audience:** choose *External*, then **Publish app** («In production»). The `drive.file` scope is not sensitive, so Google doesn't need to verify the app, and sign-ins don't expire after 7 days.
   - **Data access → Add or remove scopes:** add `https://www.googleapis.com/auth/drive.file`.
4. **Client:** go to *Clients → Create client → Android*.
   - **Package name:** `gr.wsol.billablecalls`
   - **SHA-1 certificate fingerprint:** the fingerprint of the **release key**, the same key as in the `RELEASE_KEYSTORE` secret. Get it on your computer with:
     ```
     keytool -list -v -keystore release.jks -alias <your alias>
     ```
     The command asks for the keystore password. Copy the line `SHA1: AA:BB:…`.
5. **Done:** the client is ready in a few minutes. In the app, go to *Settings → Google Drive → Sign in with Google*.

If the app says «Η σύνδεση Google δεν έχει ρυθμιστεί ακόμα», the package name or the SHA-1 doesn't match the installed APK.
