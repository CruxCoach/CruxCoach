# Reviewed APKTrack CI client

`apktrack-0.3.0-py3-none-any.whl` SHA-256: `c962a2007a36dc4078e6e647a9875e1bd7df2f7a5f405fc7baaad016617aa5ce`.

Built from the isolated APKTrack `security/scoped-ci-publication` implementation.
Source, tests and deployment instructions are retained at
`/home/myuser/security-work/apktrack-ci-boundary` on Hostinger. The corresponding
source commit and portable bundle are recorded in the migration security report.
The wheel contains Python source; no credential, signing material or runtime data.
It is vendored so first deployment does not depend on a mutable external CLI URL.
Only owner-reviewed updates may replace this client or its workflow hash pin.

The production broker must approve the Git blob ID of the reviewed publisher
workflow and must be running before this workflow is merged. Main merge remains
personal. No fallback to legacy upload credentials is permitted.
