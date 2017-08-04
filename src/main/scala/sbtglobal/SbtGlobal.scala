package sbtglobal

import sbt._, Keys._

object SbtGlobal extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = sbt.plugins.JvmPlugin

  val sbtglobalBuildFiles = SettingKey[Map[File, Seq[Byte]]]("sbtglobalBuildFiles")
  val validatePom = TaskKey[Option[Boolean]]("validatePom", "validate pom.xml for publish maven central")
  val jarSize = TaskKey[Long]("jarSize")

  private[this] def getBuildFiles (base: File) =
    ((base * "*.sbt") +++ ((base / "project") ** ("*.scala" | "*.sbt"))).get.map {
      f => f -> collection.mutable.WrappedArray.make[Byte](Hash(f))
    }.toMap

  val gitCommandParser = {
    import sbt.complete.DefaultParsers._
    val list = List(
      "add", "branch", "checkout", "cherry-pick", "commit", "diff",
      "fetch", "gc", "grep", "help", "init", "log", "mv",
      "pull", "rebase", "reflog", "rm", "status", "tag"
    ).map(token(_)).reduceLeft(_ | _)

    (Space ~> list) ~ (Space.? ~> any.*)
  }

  def checkPom(pom: scala.xml.Node): List[String] = {
    List(
      "modelVersion", "groupId", "artifactId", "version",
      "packaging", "name", "description", "url", "licenses", "developers"
    ).flatMap { tag =>
      if ((pom \ tag).isEmpty) List("<" + tag + ">") else Nil
    } ::: List(("scm", "url"), ("scm", "connection")).flatMap { case (tag1, tag2) =>
      if ((pom \ tag1 \ tag2).isEmpty) List("<" + tag1 + "><" + tag2 + ">") else Nil
    }
  }

  def openIdea(ideaCommandName: String, n: String) = {
    commands += BasicCommands.newAlias(
      ideaCommandName,
      s"""eval {sys.process.Process("/Applications/IntelliJ IDEA $n.app/Contents/MacOS/idea" :: "${(baseDirectory in LocalRootProject).value}" :: Nil).run(sys.process.ProcessLogger(_ => ()));()}"""
    )
  }

  private[this] val pluginSuffix = "Plugin"

  def addGlobalPlugin(moduleId: String, taskName: String): Seq[Def.Setting[_]] = {
    val removeCommand = "removeTemporary" + taskName

    def tempPluginDotSbtFile (base: File) =
      base / "project" / ("temporary" + taskName + ".sbt")

    Seq(
      TaskKey[Unit](removeCommand) := {
        val f = tempPluginDotSbtFile((baseDirectory in LocalRootProject).value)
        IO.delete(f)
      },
      commands += Command.command(taskName + pluginSuffix) { state =>
        val extracted = Project.extract(state)
        val f = tempPluginDotSbtFile(extracted.get(baseDirectory in LocalRootProject))
        IO.write(f, "addSbtPlugin(" + moduleId + ")")
        "reload" :: taskName :: removeCommand :: "reload" :: state
      }
    )
  }

  private[this] val timeFunc =
    """|def time[A](a: => A): A = {
       |  System.gc
       |  val s = System.nanoTime
       |  val r = a
       |  println((System.nanoTime - s) / 1000000.0)
       |  r
       |}
       |""".stripMargin

  private[this] val timeMacro =
    """|object TimeMacro {
       |  import scala.language.experimental.macros
       |  import scala.reflect.macros.blackbox.Context
       |  def timeM[A](a: A): A = macro TimeMacro.timeImpl[A]
       |  def timeImpl[A](c: Context)(a: c.Tree): c.Tree = {
       |    import c.universe._
       |    val s, r = TermName(c.freshName())
       |    q"System.gc; val $s = System.nanoTime; val $r = $a; println((System.nanoTime - $s) / 1000000.0); $r"
       |  }
       |}
       |import TimeMacro.timeM
       |""".stripMargin

  private[this] def changed(base: File, files: Map[File, Seq[Byte]]): Boolean =
    getBuildFiles(base) != files

  private[this] val dependencyUpdates = "dependencyUpdates"

  override val projectSettings = Seq[Def.SettingsDefinition](
    sbtglobalBuildFiles := sbtglobalBuildFiles.?.value.getOrElse(getBuildFiles((baseDirectory in ThisBuild).value)),
    shellPrompt in ThisBuild := (shellPrompt in ThisBuild).?.value.getOrElse { state =>
      val branch = {
        if (file(".git").exists)
          "git branch".lines_!.find {
            _.head == '*'
          }.map {
            _.drop(1)
          }.getOrElse("")
        else ""
      }

      {
        if (changed((baseDirectory in ThisBuild).value, sbtglobalBuildFiles.value))
          if (sbtVersion.value == "0.13.11") {
            // https://github.com/sbt/sbt/issues/2480
            "   build files changed. please reload project   "
          } else {
            scala.Console.RED + "Build files changed. Please reload." + scala.Console.RESET + "\n"
          }
        else ""
      } + Project.extract(state).currentRef.project + branch + " > "
    },
    initialCommands in console := {
      def containsScalaz (modules: Seq[ModuleID]) =
        modules.exists(m => m.organization == "org.scalaz" && m.configurations.isEmpty)

      val useMacro = CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v >= 11 => true
        case _ => false
      }

      (initialCommands in console).value ++ (if (useMacro) timeMacro else timeFunc) ++ (
        if (name.value == "scalaz-core" || containsScalaz(libraryDependencies.value)) {
          """import scalaz._, std.AllInstances._, std.AllFunctions._; """
        } else ""
      )
    },
    resolvers ++= {
      if (name.value == "ivy-console") Opts.resolver.sonatypeReleases :: Nil
      else Nil
    },
    scalacOptions := {
      if (name.value == "ivy-console") Seq("-deprecation", "-language:_", "-unchecked", "-Xlint")
      else scalacOptions.value
    },
    validatePom := makePom.?.value.map { f =>
      val errors = checkPom(xml.XML.loadFile(f))
      errors.foreach { e =>
        streams.value.log.error("missing tag " + e)
      }
      errors.isEmpty
    },
    commands += Command("git")(_ => gitCommandParser) { case (s, (cmd, params)) =>
      Seq("git", cmd, params.mkString).mkString(" ").!
      s
    },
    openIdea("openIdea", "CE"),
    TaskKey[Unit]("showDoc") in Compile := {
      val _ = (doc in Compile).?.value
      val out = (target in doc in Compile).value
      java.awt.Desktop.getDesktop.open(out / "index.html")
    },
    jarSize := {
      import sbinary.DefaultProtocol._
      val s = streams.value.log
      (packageBin in Compile).?.value.map { jar =>
        val current = jar.length
        val id = thisProject.value.id
        val currentSize = s"[$id] current $current"
        jarSize.previous match {
          case Some(previous) =>
            s.info(s"$currentSize, previous $previous, diff ${current - previous}")
          case None =>
            s.info(currentSize)
        }
        current
      }.getOrElse(-1)
    },
    addGlobalPlugin(""" "com.gilt" % "sbt-dependency-graph-sugar" % "0.8.2" """, "dependencySvgView"),
    addGlobalPlugin(""" "com.dwijnand.sbtprojectgraph" % "sbt-project-graph" % "0.1.0" """, "projectsGraphDot"),
    addGlobalPlugin(""" "com.timushev.sbt" % "sbt-updates" % "0.3.1" """, dependencyUpdates),
    commands ++= {
      val dependencyUpdatesPlugin = dependencyUpdates + pluginSuffix
      val dependencyUpdatesPluginPlugins = dependencyUpdatesPlugin + "Plugins"

      Seq(
        Command.command(dependencyUpdatesPluginPlugins) { state =>
          "reload plugins" :: dependencyUpdatesPlugin :: "reload return" :: state
        },
        Command.command(dependencyUpdatesPlugin + "All") { state =>
          dependencyUpdatesPlugin :: dependencyUpdatesPluginPlugins :: state
        }
      )
    },
    inConfig(Test) {
      // workaround sbt 0.13.13 bug
      // https://github.com/sbt/sbt/issues/2822
      // https://github.com/sbt/sbt/issues/1444
      definedTestNames <<= {
        import sbinary.DefaultProtocol.StringFormat
        import Cache.seqFormat
        definedTests.map(_.map(_.name).distinct) storeAs definedTestNames triggeredBy compile
      }
    }
  ).flatMap(_.settings)
}
