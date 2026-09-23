# Test signing key

`test.keystore` is a throwaway Android debug key (alias `androiddebugkey`, passwords
`android` — the standard debug defaults, not a secret). It is committed so that every
build, local or CI, signs the APK with the same certificate:

```
SHA-256  4C:FA:B2:08:2A:8F:EB:C5:97:49:76:80:98:59:A4:A3:4A:68:40:E6:8A:08:08:BC:5C:5A:ED:8D:0C:82:BC:7E
```

Android only installs an update over an existing app when both are signed with the same
key. Without a shared key, an APK built by GitHub Actions would refuse to install over
one built anywhere else.

Anyone with this repository can sign an APK as this app. That is fine for sideloaded
test builds and **not** for distribution — before publishing to a store, create a real
key, keep it out of the repository, and pass it to the build through CI secrets.
