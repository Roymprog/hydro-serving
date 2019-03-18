import java.io.File

name := "manager"

enablePlugins(sbtdocker.DockerPlugin)

unmanagedSourceDirectories in Compile += sourceManaged.value

imageNames in docker := Seq(
  ImageName(s"hydrosphere/serving-manager:${version.value}")
)

dockerfile in docker := {
  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath = (dependencyClasspath in Compile).value
  val dockerFilesLocation = baseDirectory.value / "src/main/docker/"
  val jarTarget = s"/hydro-serving/app/manager.jar"
  val osName = sys.props.get("os.name").getOrElse("unknown")

  new sbtdocker.Dockerfile {
    // Base image
    from("openjdk:8u151-jre-alpine")

    run("apk", "update")
    run("apk", "add", "jq")
    run("rm", "-rf", "/var/cache/apk/*")

    add(dockerFilesLocation, "/hydro-serving/app/")
    // Add all files on the classpath
    add(classpath.files, "/hydro-serving/app/lib/")
    // Add the JAR file
    add(jarFile, jarTarget)

    cmd("/hydro-serving/app/start.sh")
  }
}
