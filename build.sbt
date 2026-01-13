// Publishing settings
ThisBuild / organization         := "io.github.devnico"
ThisBuild / organizationName     := "slick-seeker"
ThisBuild / organizationHomepage := Some(url("https://github.com/DevNico/slick-seeker"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/DevNico/slick-seeker"),
    "scm:git@github.com:DevNico/slick-seeker.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "DevNico",
    name = "Nicolas Schlecker",
    email = "24965872+DevNico@users.noreply.github.com",
    url = url("https://github.com/DevNico")
  )
)

ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage := Some(url("https://devnico.github.io/slick-seeker"))

ThisBuild / publishTo              := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := Sonatype.sonatypeCentralHost
ThisBuild / publishMavenStyle      := true
ThisBuild / pomIncludeRepository   := { _ => false }
ThisBuild / makePomConfiguration := makePomConfiguration.value.withConfigurations(
  configurations = Vector(Compile, Runtime, Optional)
)

// Version and Scala settings
val slickSeekerVersion = "0.5.1"

val scala3Version   = "3.3.5"
val scala213Version = "2.13.16"

val slickVersion     = "3.6.1"
val scalatestVersion = "3.2.19"

lazy val commonSettings = Seq(
  version            := slickSeekerVersion,
  scalaVersion       := scala3Version,
  crossScalaVersions := Seq(scala213Version, scala3Version),
  scalacOptions ++= Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-Xfatal-warnings"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, minor)) if minor >= 5 =>
      Seq("-Xkind-projector:underscores") // Use -X instead of -Y in 3.5+
    case Some((3, _)) =>
      Seq("-Ykind-projector:underscores")
    case Some((2, 13)) =>
      Seq("-language:higherKinds", "-Wconf:cat=unused:info")
    case _ =>
      Seq.empty
  }),
  libraryDependencies += "org.scalatest" %% "scalatest" % scalatestVersion % Test
)

lazy val root = (project in file("."))
  .settings(
    name           := "slick-seeker-root",
    publish / skip := true
  )
  .aggregate(core, playJson)

lazy val core = (project in file("slick-seeker"))
  .settings(
    commonSettings,
    name := "slick-seeker",
    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick"     % slickVersion,
      "com.h2database"      % "h2"        % "2.4.240" % Test,
      "org.slf4j"           % "slf4j-nop" % "2.0.17"  % Test
    )
  )

lazy val playJson = (project in file("addons/play-json"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    commonSettings,
    name := "slick-seeker-play-json",
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => Seq("org.playframework" %% "play-json" % "3.0.5")
      case Some((2, _)) => Seq("com.typesafe.play" %% "play-json" % "2.10.7")
      case _            => Seq.empty
    })
  )
