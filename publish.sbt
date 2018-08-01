
publishMavenStyle := true

publishTo := sonatypePublishTo.value

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

sonatypeProfileName := "com.gilt"

pomExtra := {
  <url>https://github.com/gilt/sbt-aws-lambda</url>
  <scm>
    <url>git@github.com:gilt/sbt-aws-lambda.git</url>
    <connection>scm:git:git@github.com:gilt/sbt-aws-lambda.git</connection>
  </scm>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>bstjohn</id>
      <name>Brendan St John</name>
      <url>https://github.com/stjohnb</url>
    </developer>
    <developer>
      <id>fiadliel</id>
      <name>Gary Coady</name>
      <url>https://github.com/fiadliel</url>
    </developer>
    <developer>
      <id>sullis</id>
      <name>Sean Sullivan</name>
      <url>https://github.com/sullis</url>
    </developer>
  </developers>
}
