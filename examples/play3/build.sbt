name := "slick-seeker-play3-example"

version := "1.0.0-SNAPSHOT"

scalaVersion := "3.3.5"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    scalacOptions := Seq(
      "-encoding",
      "utf8",
      "-feature",
      "-unchecked",
      "-deprecation"
    )
  )

libraryDependencies ++= Seq(
  guice,
  jdbc,
  "io.github.devnico"  %% "slick-seeker"           % "0.1.0",
  "io.github.devnico"  %% "slick-seeker-play-json" % "0.1.0",
  "org.playframework"  %% "play-slick"             % "6.1.0",
  "org.playframework"  %% "play-slick-evolutions"  % "6.1.0",
  "com.h2database"      % "h2"                     % "2.4.240",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
)
