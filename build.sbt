name := "CE-3 Spinner Tutorial"
scalaVersion := "2.13.1" // or any other Scala version >= 2.11.12
organization := "com.cespinner"

mainClass in (assembly) := Some("com.cespinner.IOSpinner")
assemblyJarName in assembly := "ce-spinner.jar"


libraryDependencies += "org.typelevel" %% "cats-effect" % "3.0.0"
