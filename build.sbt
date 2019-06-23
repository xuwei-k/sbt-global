sbtPlugin := true

name := "sbt-global"

organization := "com.github.xuwei-k"

startYear := Some(2017)

scalacOptions ++= (
  "-deprecation" ::
  "-unchecked" ::
  "-language:existentials" ::
  "-language:higherKinds" ::
  "-language:implicitConversions" ::
  Nil
)

val tagName = Def.setting {
  s"v${if (releaseUseGlobalVersion.value) (version in ThisBuild).value else version.value}"
}

val tagOrHash = Def.setting {
  if (isSnapshot.value) sys.process.Process("git rev-parse HEAD").lines_!.head
  else tagName.value
}

scalacOptions in (Compile, doc) ++= {
  val tag = tagOrHash.value
  Seq(
    "-sourcepath",
    (baseDirectory in LocalRootProject).value.getAbsolutePath,
    "-doc-source-url",
    s"https://github.com/xuwei-k/sbt-global/tree/${tag}€{FILE_PATH}.scala"
  )
}

pomExtra :=
<url>https://github.com/xuwei-k/sbt-global</url>
<developers>
  <developer>
    <id>xuwei-k</id>
    <name>Kenji Yoshida</name>
    <url>https://github.com/xuwei-k</url>
  </developer>
</developers>
<scm>
  <url>git@github.com:xuwei-k/sbt-global.git</url>
  <connection>scm:git:git@github.com:xuwei-k/sbt-global.git</connection>
  <tag>{tagOrHash.value}</tag>
</scm>

licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT"))
import sbtrelease._
import sbtrelease.ReleaseStateTransformations._
import com.typesafe.sbt.pgp.PgpKeys
import xerial.sbt.Sonatype

Sonatype.sonatypeSettings

val sonatypeURL =
"https://oss.sonatype.org/service/local/repositories/"

val updateReadme = TaskKey[File]("updateReadme")

updateReadme := {
  val snapshotOrRelease = if(isSnapshot.value) "snapshots" else "releases"
  val readme = "README.md"
  val readmeFile = file(readme)
  val newReadme = Predef.augmentString(IO.read(readmeFile)).lines.map{ line =>
    val matchReleaseOrSnapshot = line.contains("SNAPSHOT") == isSnapshot.value
    if(line.startsWith("addSbtPlugin") && matchReleaseOrSnapshot){
      s"""addSbtPlugin("${organization.value}" % "${name.value}" % "${version.value}")"""
    }else line
  }.mkString("", "\n", "\n")
  IO.write(readmeFile, newReadme)
  val git = new Git((baseDirectory in LocalRootProject).value)
  val log = new scala.sys.process.ProcessLogger {
    private[this] val l = state.value.log
    def buffer[T](f: => T): T = f
    def err(s: => String): Unit = l.error(s)
    def out(s: => String): Unit = l.info(s)
  }
  git.add(readme) ! log
  git.commit(message = "update " + readme, sign = false, signOff = false) ! log
  sys.process.Process("git diff HEAD^") ! log
  readmeFile
}

import ReleaseTransformations._

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  releaseStepTask(updateReadme),
  tagRelease,
  releaseStepCommand("publishSigned"),
  setNextVersion,
  commitNextVersion,
  releaseStepTask(updateReadme),
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)

credentials ++= PartialFunction.condOpt(sys.env.get("SONATYPE_USER") -> sys.env.get("SONATYPE_PASSWORD")){
  case (Some(user), Some(pass)) =>
    Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
}.toList

publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)
