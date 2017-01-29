enablePlugins(BuildInfoPlugin)

buildInfoKeys := Seq[BuildInfoKey](
  organization,
  name,
  version,
  scalaVersion,
  sbtVersion,
  scalacOptions,
  licenses
)

buildInfoPackage := "sbtglobal"

buildInfoObject := "BuildInfoSbtGlobal"
