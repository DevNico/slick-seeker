# Release Process

1. Update version in `build.sbt` (line 33)
2. Commit: `git commit -am "chore: release vX.Y.Z"`
3. Tag: `git tag vX.Y.Z`
4. Push: `git push && git push --tags`

GitHub Actions will automatically publish to Maven Central and create a GitHub release.
