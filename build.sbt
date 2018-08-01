import scala.sys.process._

name := "sbt-aws-lambda"

organization := "com.gilt.sbt"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)

version in ThisBuild := "git describe --tags --always --dirty".!!.trim.replaceFirst("^v", "")

libraryDependencies += {
  val sbtV     = (sbtBinaryVersion in pluginCrossBuild).value
  val scalaV   = (scalaBinaryVersion in update).value
  val assembly = "com.eed3si9n" % "sbt-assembly" % "0.14.7"
  Defaults.sbtPluginExtra(assembly, sbtV, scalaV)
}

val awsSdkVersion = "1.11.376"

libraryDependencies ++= Seq(
  "com.amazonaws"  % "aws-java-sdk-iam"    % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-lambda" % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-s3"     % awsSdkVersion
)

javaVersionPrefix in javaVersionCheck := Some("1.8")

crossSbtVersions := List("0.13.17", "1.1.6")
