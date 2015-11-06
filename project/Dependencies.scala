import sbt._
import play.sbt.PlayImport.{filters,ws}

object Dependencies {
  private object deps {
    // shared dependencies
    val akka = "com.typesafe.akka" %% "akka-actor" % "2.4.0"
    val akkaAgent = "com.typesafe.akka" %% "akka-agent" % "2.4.0"
    val akkaRemote = "com.typesafe.akka" %% "akka-remote" % "2.4.0"
    val akkaTestkit = "com.typesafe.akka" %% "akka-testkit"  % "2.4.0"
    val asyncHttpClient = "com.ning" % "async-http-client" % "1.9.31"
    val awsCore = "com.amazonaws" % "aws-java-sdk-core" % "1.10.32"
    val awsS3 = "com.amazonaws" % "aws-java-sdk-s3" % "1.10.32"
    val bcrypt = "com.github.t3hnar" %% "scala-bcrypt" % "2.4"
    val commonsIo = "commons-io" % "commons-io" % "2.4"
    val config = "com.typesafe" % "config" % "1.3.0"
    val elasticSearch = "org.elasticsearch" % "elasticsearch" % "1.7.1"
    val elasticSearchIcu = "org.elasticsearch" % "elasticsearch-analysis-icu" % "2.7.0" // find version at https://github.com/elastic/elasticsearch-analysis-icu
    val flywayDb = "org.flywaydb" % "flyway-core" % "3.2.1"
    val guava = "com.google.guava" % "guava" % "18.0"
    val hikariCp = "com.zaxxer" % "HikariCP" % "2.4.1"
    val janino = "org.codehaus.janino" % "janino" % "2.7.8" // Runtime Java compiler -- for logback-test.xml
    val javaxMail = "javax.mail" % "mail" % "1.4.7"
    val jna = "net.java.dev.jna" % "jna" % "4.2.1" // for ElasticSearch, partially to ignore a warning
    val joddWot = "org.jodd" % "jodd-wot" % "3.3.8"
    val junitInterface = "com.novocode" % "junit-interface" % "0.9"
    val junit = "junit" % "junit-dep" % "4.11"
    val logback = "ch.qos.logback" % "logback-classic" % "1.1.3"
    val log4jBridge = "org.slf4j" % "log4j-over-slf4j" % "1.7.12" // for ElasticSearch
    val mimeTypes = "org.overviewproject" % "mime-types" % "0.0.2"
    val mockito = "org.mockito" % "mockito-all" % "1.9.5"
    val openCsv = "com.opencsv" % "opencsv" % "3.4"
    val owaspEncoder = "org.owasp.encoder" % "encoder" % "1.1"
    val parserCombinators = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4" // for Query
    // pdfocr is a SNAPSHOT right now, and as such it doesn't deploy reliably.
    // (Yes, we control pdfocr. But its dependency, pdfbox-2.0.0, is a SNAPSHOT
    // too. RC1 has a bug that reliably crashes when scanning for fonts.)
    // TODO release pdfocr and depend on a released version. Then delete all
    // the jarfiles in documentset-worker/lib/
    //val pdfocr = "org.overviewproject" %% "pdfocr" % "0.0.1-SNAPSHOT" // TODO release it, then use it
    val pgSlick = "com.github.tminglei" %% "slick-pg" % "0.10.1"
    val playJson = "com.typesafe.play" %% "play-json" % play.core.PlayVersion.current
    val playMailer = "com.typesafe.play" %% "play-mailer" % "3.0.1"
    val playPluginsUtil = "com.typesafe.play.plugins" %% "play-plugins-util" % "2.3.0"
    val playStreams = "com.typesafe.play" %% "play-streams-experimental" % play.core.PlayVersion.current
    val playTest = "com.typesafe.play" %% "play-test" % play.core.PlayVersion.current
    val postgresql = "org.postgresql" % "postgresql" % "9.4-1205-jdbc42"
    val redis = "net.debasishg" %% "redisreact" % "0.8"
    val scalaArm = "com.jsuereth" %% "scala-arm" % "1.4"
    val scallop = "org.rogach" %% "scallop" % "0.9.5"
    val slick = "com.typesafe.slick" %% "slick" % "3.1.0"
    val specs2 = "org.specs2" %% "specs2" % "2.3.13"
  }

  val serverDependencies = Seq(
    deps.bcrypt,
    deps.openCsv,
    deps.owaspEncoder,
    deps.playMailer,
    deps.playPluginsUtil,
    deps.playStreams,
    deps.slick,
    filters,
    ws,
    deps.log4jBridge % "test",
    deps.joddWot % "test",
    deps.playTest % "test"
  )

  val dbEvolutionApplierDependencies = Seq(
    deps.config,
    deps.flywayDb,
    deps.logback,
    deps.postgresql
  )

  // Dependencies for the project named 'common'. Not dependencies common to all projects...
  val commonDependencies = Seq(
    deps.akka,
    deps.akkaRemote,
    deps.asyncHttpClient,
    deps.awsS3,
    deps.commonsIo,
    deps.elasticSearch exclude("log4j", "log4j"),
    deps.elasticSearchIcu exclude("log4j", "log4j"),
    deps.guava, // Textify
    deps.hikariCp,
    deps.jna, // Make ElasticSearch client happy
    deps.log4jBridge, // ElasticSearch
    deps.logback,
    deps.parserCombinators,
    deps.pgSlick,
    deps.playJson,
    deps.postgresql,
    deps.redis,
    deps.slick,
    deps.akkaTestkit % "test",
    deps.janino % "test", // See logback-test.xml
    deps.junitInterface % "test",
    deps.junit % "test",
    deps.mockito % "test",
    deps.specs2 % "test"
  )

  val commonTestDependencies = Seq(
    deps.akka,
    deps.akkaTestkit,
    deps.junit,
    deps.junitInterface,
    deps.logback,
    deps.mockito,
    deps.specs2
  )

  val workerDependencies = Seq(
    deps.javaxMail,
    deps.openCsv,
    deps.playStreams,
    deps.janino % "test" // See logback-test.xml
  )
  
  val documentSetWorkerDependencies = Seq(
    deps.akkaAgent,
    deps.akkaRemote,
    deps.javaxMail,
    deps.logback,
    deps.mimeTypes,
    //deps.pdfocr,
    deps.janino % "test" // See logback-test.xml
  )

  val searchIndexDependencies = Seq(
    deps.elasticSearch,
    deps.elasticSearchIcu,
    deps.jna // actually useful here
  )

  val runnerDependencies = Seq(
    deps.postgresql,
    deps.scalaArm,
    deps.scallop,
    deps.mockito % "test",
    deps.specs2 % "test"
  )
}
