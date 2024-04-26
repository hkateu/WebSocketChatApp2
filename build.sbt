val Http4sVersion = "0.23.23"
val CirceVersion = "0.14.6"
val LogbackVersion = "1.4.11"
val CatsParseVersion = "0.3.10"
val WeaverVersion = "0.8.3"
val Http4sEmberClientVersion = "0.23.23-101-eb5dd80-SNAPSHOT"
val RedisVersion = "1.5.2"
val SkunkVersion = "0.6.3"

lazy val root = (project in file("."))
  .settings(
    organization := "rockthejvm",
    name := "websockets",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "3.3.3",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sEmberClientVersion,
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "org.typelevel" %% "cats-parse" % CatsParseVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "dev.profunktor" %% "redis4cats-effects" % RedisVersion,
      "dev.profunktor" %% "redis4cats-streams" % RedisVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.disneystreaming" %% "weaver-cats" % WeaverVersion % Test
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % CirceVersion),
    resolvers += "s01-oss-sonatype-org-snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots/",
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )
