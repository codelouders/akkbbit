import sbt._

object Dependencies {
  val slf4jVersion = "1.7.12"
  val logbackVersion = "1.2.3"
  val akkaVersion = "2.5.19"
  val scalaLoggingVersion = "3.9.0"
  val rabbitLibVersion = "5.5.1"

  val deps = Seq(
    "com.rabbitmq" % "amqp-client" % rabbitLibVersion,
    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "org.slf4j" % "log4j-over-slf4j" % slf4jVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion % "runtime",
    "ch.qos.logback" % "logback-core" % logbackVersion % "runtime",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.2",
    "org.codehaus.janino" % "janino" % "3.0.11",
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
  )

  val testDeps = Seq(
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion,
    "org.scalatest" %% "scalatest" % "3.0.5"
  ).map(_ % Test)

}
