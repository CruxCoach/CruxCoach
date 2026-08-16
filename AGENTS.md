## APKTrack publishing

- Configuration is in `.apktrack/project.toml` (schema 2). Public tracks are `stable`
  (production, manual-only) and `fips` (development).
- Finish agent-owned changes as a focused commit: stage only the files/hunks owned by the task,
  inspect the cached diff, and run the relevant verification before committing. Never absorb
  unrelated user or other-agent changes merely to make the worktree clean.
- After every successful requested FIPS APK build, publish the result to the `fips` track. A
  build from a dirty worktree has no honest commit provenance: stop and report the modified files
  instead of publishing it. Never silently commit unrelated user changes.
- Any workflow that would install a newly built FIPS APK with ADB must also publish that exact
  committed build to the `fips` track. If ADB or the target device is unavailable, do not skip
  APKTrack: build and publish the committed APK anyway, then report the device-install blocker.
- Build with `./gradlew :androidApp:assembleDebug`. Exactly one artifact must exist at
  `androidApp/build/outputs/apk/debug/androidApp-debug.apk`; stop if it is missing or ambiguous.
- Use the lowercase full SHA from `git rev-parse HEAD` for both `--commit` and the idempotency key.
  Use the current branch/worktree name for `--branch`.
- Obtain the scoped secret from the local secret provider as `APKTRACK_AGENT_TOKEN`. Never print,
  persist, commit, put in a URL, or include its value in a prompt or log.
- Publish only after the build succeeds:

  ```bash
  apktrack publish-build androidApp/build/outputs/apk/debug/androidApp-debug.apk \
    --config .apktrack/project.toml --track fips --branch <branch-or-worktree> \
    --commit <lowercase-full-git-sha>
  ```

- The command waits for the terminal state. A queued response is not success; diagnose a returned
  job with `apktrack build-status <job-id> --server-url https://stats.cruxcoach.org/apktrack`.
- On success report the stable track URL, APK SHA-256, branch, and commit. On failure report the
  sanitized error. Never manually delete Blossom blobs; manifest replacement and grace-period GC
  own their lifecycle.
