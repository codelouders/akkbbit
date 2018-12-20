name := "akkbbit"

organization := "com.codelouders"

version := "0.1"

scalaVersion := "2.12.8"

libraryDependencies ++= {
  val slf4jVersion = "1.7.12"
  val logbackVersion = "1.1.3"
  val akkaVersion = "2.5.17"
  val scalaLoggingVersion = "3.5.0"
  val rabbitLibVersion = "5.5.1"
  Seq(
    "com.rabbitmq" % "amqp-client" % rabbitLibVersion,

    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "org.slf4j" % "log4j-over-slf4j" % slf4jVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion % "runtime",
    "ch.qos.logback" % "logback-core" % logbackVersion % "runtime",
    "net.logstash.logback" % "logstash-logback-encoder" % "4.6",
    "org.codehaus.janino" % "janino" % "2.7.8",

    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
    // Test dependencies
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test"
  )
}
