## APKTrack publishing

- Configuration is in `.apktrack/project.toml` (schema 1). Public tracks are `stable`
  (production, manual-only) and `fips` (development).
- After every successful requested FIPS APK build, publish the result to the `fips` track. A
  build from a dirty worktree has no honest commit provenance: stop and report the modified files
  instead of publishing it. Never silently commit unrelated user changes.
- Build with `./gradlew :androidApp:assembleDebug`. Exactly one artifact must exist at
  `androidApp/build/outputs/apk/debug/androidApp-debug.apk`; stop if it is missing or ambiguous.
- Use the lowercase full SHA from `git rev-parse HEAD` for both `--commit` and the idempotency key.
  Use the current branch/worktree name for `--branch`.
- Obtain the scoped secret from the local secret provider as `APKTRACK_AGENT_TOKEN`. Never print,
  persist, commit, put in a URL, or include its value in a prompt or log.
- Publish only after the build succeeds:

  ```bash
  apktrack publish-build androidApp/build/outputs/apk/debug/androidApp-debug.apk \
    --project cruxcoach --track fips --branch <branch-or-worktree> \
    --commit <lowercase-full-git-sha> --token-env APKTRACK_AGENT_TOKEN \
    --server-url http://127.0.0.1:18080 --idempotency-key cruxcoach-fips-<sha>
  ```

- A queued response is not success. Poll with `apktrack build-status <job-id>
  --token-env APKTRACK_AGENT_TOKEN --server-url http://127.0.0.1:18080` until the terminal state.
- On success report the stable track URL, APK SHA-256, branch, and commit. On failure report the
  sanitized error. Never manually delete Blossom blobs; manifest replacement and grace-period GC
  own their lifecycle.
