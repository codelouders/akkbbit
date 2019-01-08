import sbt.Keys._
import Dependencies._

lazy val root = (project in file("."))
    .settings(
      name := "akkbbit",
      organization := "com.codelouders",
      version := "0.1.6",
      scalaVersion := "2.12.8"
    )
    .settings(
      libraryDependencies ++= deps,
      libraryDependencies ++= testDeps
    )
    .settings(
      licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
    )
