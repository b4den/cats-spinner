lazy val commonSettings = Seq(
  name := "CE-3 Spinner Tutorial",
  scalaVersion := "2.13.1",
  organization := "com.cespinner",
)

lazy val root = project.in(file(".")).
  aggregate(cespinner.js, cespinner.jvm).
  settings(
    publish := {},
    publishLocal := {},
  )

  lazy val cespinner = crossProject(JSPlatform, JVMPlatform).in(file(".")).
  settings(commonSettings: _*).
  settings(
    //name := "cespinner",
    //version := "0.1-SNAPSHOT",
    libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.0.0"
  ).
  jvmSettings(
    // Add JVM-specific settings here
    assemblyJarName in assembly := "ce-spinner.jar"
  ).
  jsSettings(
    // Add JS-specific settings here
    scalaJSUseMainModuleInitializer := true,
  )

mainClass in (assembly) := Some("com.cespinner.IOSpinner")
