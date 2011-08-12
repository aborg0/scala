import sbt._
import Keys._
import partest._

object ScalaBuild extends Build {
  def projectSettings: Seq[Setting[_]] = Seq(
    doc in Compile <<= (doc in documentation in Compile).identity,
    packageBin in Compile <<= Seq(scalaLibrary, scalaCompiler, continuationsPlugin, jline).map(p => packageBin in p in Compile).join.map(_.map(_.head)),
    // TODO - Make sure scalaLibrary has packageDoc + packageSrc from documentation attached...
    publish <<= Seq(scalaLibrary, scalaCompiler, continuationsPlugin, jline).map(p => publish in p).join.map(_.map(_.head)),
    publishLocal <<= Seq(scalaLibrary, scalaCompiler, continuationsPlugin, jline).map(p => publishLocal in p).join.map(_.map(_.head)),
    packageDoc in Compile <<= (packageDoc in documentation in Compile).identity,
    packageSrc in Compile <<= (packageSrc in documentation in Compile).identity,
    test <<= (runPartest in testsuite).identity
  )
  // Note: Root project is determined by lowest-alphabetical project that has baseDirectory as file(".").  we use aaa_ to 'win'.
  lazy val aaa_root = Project("scala", file(".")) settings(projectSettings:_*) // TODO - aggregate on, say... quick

  // External dependencies used for various projects
  lazy val ant = libraryDependencies += "org.apache.ant" % "ant" % "1.8.2"

  // These are setting overrides for most artifacts in the Scala build file.
  def settingOverrides: Seq[Setting[_]] = Seq(
                             crossPaths := false,
                             publishArtifact in packageDoc := false,
                             publishArtifact in packageSrc := false,
                             target <<= (baseDirectory, name) apply (_ / "target" / _),
                             (classDirectory in Compile) <<= target(_ / "classes"),
                             javacOptions ++= Seq("-target", "1.5", "-source", "1.5"),
                             scalaSource in Compile <<= (baseDirectory, name) apply (_ / "src" / _),
                             javaSource in Compile <<= (baseDirectory, name) apply (_ / "src" / _),
                             autoScalaLibrary := false,
                             unmanagedJars in Compile := Seq(),
                             // Most libs in the compiler use this order to build.
                             compileOrder in Compile :== CompileOrder.JavaThenScala
                            )
  // TODO - Figure out a way to uniquely determine a version to assign to Scala builds...
  def currentUniqueRevision = "0.1"

  // --------------------------------------------------------------
  //  Libraries used by Scalac that change infrequently
  //  (or hopefully so).
  // --------------------------------------------------------------

  // Jline nested project.   Compile this sucker once and be done.
  lazy val jline = Project("jline", file("src/jline"))
  // Fast Java Bytecode Generator (nested in every scala-compiler.jar)
  lazy val fjbg = Project("fjbg", file(".")) settings(settingOverrides : _*)

  // Forkjoin backport
  lazy val forkjoin = Project("forkjoin", file(".")) settings(settingOverrides : _*)

  // MSIL code generator
  // TODO - This probably needs to compile in the layers, but Sabbus
  // had it building against locker, so we'll do worse and build
  // build against STARR for now.
  lazy val msilSettings = settingOverrides ++ Seq(
                            defaultExcludes ~= (_ || "tests"),
                            javacOptions ++= Seq("-source", "1.4"),
                            STARR                         
                          )
  lazy val msil = Project("msil", file(".")) settings(msilSettings: _*)


  // --------------------------------------------------------------
  //  The magic kingdom.
  //  Layered compilation of Scala.
  //   Stable Reference -> Locker ('Lockable' dev version) -> Quick -> Strap (Binary compatibility testing)
  // --------------------------------------------------------------

  // Need a report on this...
  // TODO - Resolve STARR from a repo..
  def STARR = scalaInstance <<= appConfiguration map { app =>
    val launcher = app.provider.scalaProvider.launcher
    ScalaInstance(
      file("lib/scala-library.jar"),
      file("lib/scala-compiler.jar"),
      launcher,
      file("lib/fjbg.jar"),
      file("lib/forkjoin.jar"),
      file("lib/jline.jar"))
  }

  // Locker is a lockable Scala compiler that can be built of 'current' source to perform rapid development.
  lazy val (lockerLib, lockerComp) = makeLayer("locker", STARR)
  lazy val locker = Project("locker", file(".")) aggregate(lockerLib, lockerComp)

  // Quick is the general purpose project layer for the Scala compiler.
  lazy val (quickLib, quickComp) = makeLayer("quick", makeScalaReference("locker", lockerLib, lockerComp, fjbg))
  lazy val quick = Project("quick", file(".")) aggregate(quickLib, quickComp)

  // Reference to quick scala instance.
  def quickScalaInstance = makeScalaReference("quick", quickLib, quickComp, fjbg)
  def quickScalaLibraryDependency = unmanagedClasspath in Compile <++= (exportedProducts in quickLib in Compile).identity
  def quickScalaCompilerDependency = unmanagedClasspath in Compile <++= (exportedProducts in quickComp in Compile).identity


  // --------------------------------------------------------------
  //  Helper methods for layered compilation.
  // --------------------------------------------------------------
  def makeScalaReference(layer : String, library: Project, compiler: Project, fjbg: Project) =
     scalaInstance <<= (appConfiguration,
                        (exportedProducts in library in Compile),
                        (exportedProducts in compiler in Compile),
                        (exportedProducts in fjbg in Compile)) map {
    (app, lib: Classpath, comp: Classpath, fjbg : Classpath) =>
    val launcher = app.provider.scalaProvider.launcher
    // TODO - Figure out a better way here, or bug Mark.
    if (lib.length != 1 || comp.length != 1) {
      error("Cannot build a ScalaReference with more than one classpath element")
    }
    ScalaInstance(
      layer + "-" + currentUniqueRevision,
      lib.head.data,
      comp.head.data,
      launcher,
      (fjbg.files:_*))
  }
  
  // Creates a "layer" of Scala compilation.  That is, this will build the next version of Scala from a previous version.
  // Returns the library project and compiler project from the next layer.
  // Note:  The library and compiler are not *complete* in the sense that they are missing things like "actors" and "fjbg".
  def makeLayer(layer: String, referenceScala: Setting[Task[ScalaInstance]]) : (Project, Project) = {
    // TODO - Make version number for library...
    val library = Project(layer + "-library", file("."))  settings( (settingOverrides ++
      Seq(version := layer,
          // TODO - use depends on.
          unmanagedClasspath in Compile <<= (exportedProducts in forkjoin in Compile).identity,
          managedClasspath in Compile := Seq(),
          scalaSource in Compile <<= (baseDirectory) apply (_ / "src" / "library"),
          resourceDirectory in Compile <<= baseDirectory apply (_ / "src" / "library"),   
          defaultExcludes in unmanagedResources := ("*.scala" | "*.java"),
          // TODO - Allow other scalac option settings.
          scalacOptions in Compile <++= (scalaSource in Compile) map (src => Seq("-sourcepath", src.getAbsolutePath)),
          classpathOptions := ClasspathOptions.manual,
          referenceScala
      )) :_*)

    // Define the compiler
    val compiler = Project(layer + "-compiler", file(".")) settings((settingOverrides ++
      Seq(version := layer,
        scalaSource in Compile <<= (baseDirectory) apply (_ / "src" / "compiler"),
        resourceDirectory in Compile <<= baseDirectory apply (_ / "src" / "compiler"),    
        defaultExcludes in unmanagedResources := "*.scala",
        // Note, we might be able to use the default task, but for some reason ant was filtering files out.  Not sure what's up, but we'll
        // stick with that for now.
        unmanagedResources in Compile <<= (baseDirectory) map {
          (bd) =>
            val dirs = Seq(bd / "src" / "compiler")
		dirs.descendentsExcept( ("*.html" | "*.gif" | "*.png" | "*.js" | "*.css" | "*.tmpl" | "*.swf" | "*.properties"),"*.scala").get
        },
        // TODO - Use depends on *and* SBT's magic dependency mechanisms...
        unmanagedClasspath in Compile <<= Seq(forkjoin, library, fjbg, jline, msil).map(exportedProducts in Compile in _).join.map(_.map(_.flatten)),
        classpathOptions := ClasspathOptions.manual,
        ant,
        referenceScala
        )
      ):_*)

    // Return the generated projects.
    (library, compiler)
  }

  // --------------------------------------------------------------
  //  Projects dependent on layered compilation (quick)
  // --------------------------------------------------------------
  // TODO - in sabbus, these all use locker to build...
  lazy val dependentProjectSettings = settingOverrides ++ Seq(quickScalaInstance, quickScalaLibraryDependency)
  lazy val actors = Project("actors", file(".")) settings(dependentProjectSettings:_*) dependsOn(forkjoin)
  lazy val dbc = Project("dbc", file(".")) settings(dependentProjectSettings:_*)
  lazy val swing = Project("swing", file(".")) settings(dependentProjectSettings:_*) dependsOn(actors)
  lazy val scalacheck = Project("scalacheck", file(".")) settings(dependentProjectSettings:_*)

  // Things that compile against the compiler.
  lazy val compilerDependentProjectSettings = dependentProjectSettings ++ Seq(quickScalaCompilerDependency)
  lazy val scalap = Project("scalap", file(".")) settings(compilerDependentProjectSettings:_*)
  lazy val partestSettings = compilerDependentProjectSettings :+ ant
  lazy val partest = Project("partest", file(".")) settings(partestSettings:_*)  dependsOn(actors,forkjoin,scalap)
  // TODO - generate scala properties file...

  // --------------------------------------------------------------
  //  Continuations plugin + library
  // --------------------------------------------------------------
  lazy val continuationsPluginSettings = compilerDependentProjectSettings ++ Seq(
    scalaSource in Compile <<= baseDirectory(_ / "src/continuations/plugin/"),
    resourceDirectory in Compile <<= baseDirectory(_ / "src/continuations/plugin/"),
    exportJars := true,
    name := "continuations"  // Note: This artifact is directly exported.
  )
  lazy val continuationsPlugin = Project("continuations-plugin", file(".")) settings(continuationsPluginSettings:_*)
  lazy val continuationsLibrarySettings = dependentProjectSettings ++ Seq(
    scalaSource in Compile <<= baseDirectory(_ / "src/continuations/library/"),
    scalacOptions in Compile <++= (exportedProducts in Compile in continuationsPlugin) map { 
     case Seq(cpDir) => Seq("-Xplugin-require:continuations", "-P:continuations:enable", "-Xplugin:"+cpDir.data.getAbsolutePath)
    }
  )
  lazy val continuationsLibrary = Project("continuations-library", file(".")) settings(continuationsLibrarySettings:_*)

  // --------------------------------------------------------------
  //  Real Library Artifact
  // --------------------------------------------------------------
  val allSubpathsCopy = (dir: File) => (dir.*** --- dir) x (relativeTo(dir)|flat)
  def productTaskToMapping(products : Task[Seq[File]]) = products map { ps => ps flatMap { p => allSubpathsCopy(p) } }
  // TODO - Create a task to write the version properties file and add the mapping to this task...
  lazy val packageScalaLibBinTask = Seq(quickLib, continuationsLibrary, dbc, actors, swing, forkjoin).map(p => products in p in Compile).join.map(_.map(_.flatten)).map(productTaskToMapping)
  lazy val scalaLibArtifactSettings : Seq[Setting[_]] = inConfig(Compile)(Defaults.packageTasks(packageBin, packageScalaLibBinTask)) ++ Seq(
    name := "scala-library",
    crossPaths := false,
    exportJars := true,
    autoScalaLibrary := false,
    unmanagedJars in Compile := Seq()
  )
  lazy val scalaLibrary = Project("scala-library", file(".")) settings(scalaLibArtifactSettings:_*)

  // --------------------------------------------------------------
  //  Real Compiler Artifact
  // --------------------------------------------------------------
  lazy val packageScalaBinTask = Seq(quickComp, fjbg, msil).map(p => products in p in Compile).join.map(_.map(_.flatten)).map(productTaskToMapping)
  lazy val scalaBinArtifactSettings : Seq[Setting[_]] = inConfig(Compile)(Defaults.packageTasks(packageBin, packageScalaBinTask)) ++ Seq(
    name := "scala-compiler",
    crossPaths := false,
    exportJars := true,
    autoScalaLibrary := false,
    unmanagedJars in Compile := Seq()
  )
  lazy val scalaCompiler = Project("scala-compiler", file(".")) settings(scalaBinArtifactSettings:_*) dependsOn(scalaLibrary)


  // --------------------------------------------------------------
  //  Testing
  // --------------------------------------------------------------
  lazy val testsuiteSetttings: Seq[Setting[_]] = compilerDependentProjectSettings ++ Seq(
    unmanagedBase <<= baseDirectory / "test/files/lib",
    partestRunner <<= partestRunnerTask(fullClasspath in Runtime),
    runPartest <<= runPartestTask(partestRunner, baseDirectory),
    autoScalaLibrary := false
  )
  val testsuite = Project("testsuite", file(".")) settings(testsuiteSetttings:_*) dependsOn(partest,swing,scalaLibrary,scalaCompiler,fjbg)

  // --------------------------------------------------------------
  //  Generating Documentation.
  // --------------------------------------------------------------

  // Scaladocs
  def distScalaInstance = makeScalaReference("dist", scalaLibrary, scalaCompiler, fjbg)
  lazy val documentationSettings: Seq[Setting[_]] = dependentProjectSettings ++ Seq(
    defaultExcludes in Compile :== (".*"  - ".") || HiddenFileFilter,
    sourceFilter in Compile :== ("*.scala"),
    unmanagedSourceDirectories in Compile <<= baseDirectory apply { dir =>
      Seq(dir / "src" / "library" / "scala", dir / "src" / "actors", dir / "src" / "swing", dir / "src" / "continuations" / "library")
    },
    compile := inc.Analysis.Empty,
    scaladocOptions in Compile <++= (baseDirectory) map (bd => 
      Seq("-sourcepath", (bd / "src" / "library").getAbsolutePath,
          "-doc-no-compile", (bd / "src" / "library-aux").getAbsolutePath,
          "-doc-source-url", "https://lampsvn.epfl.ch/trac/scala/browser/scala/trunk/src/€{FILE_PATH}.scala#L1"
      )),
    classpathOptions in Compile := ClasspathOptions.manual
  )
  lazy val documentation = Project("documentation", file(".")) settings(documentationSettings: _*) dependsOn(quickLib, quickComp, actors, fjbg, forkjoin, swing, continuationsLibrary)

  // This project will generate man pages (in man1 and html) for scala.
  val runManmakerMan = TaskKey[Unit]("make-man", "Runs the man maker project to generate man pages")
  val runManmakerHtml = TaskKey[Unit]("make-html", "Runs the man maker project to generate html pages")
  def runManmakerTask(classpath: ScopedTask[Classpath], scalaRun: ScopedTask[ScalaRun], mainClass: String, dir: String, ext: String): Project.Initialize[Task[Unit]] =
    (classpath, runner, streams, target) map { (cp, runner, s, target) =>
      val binaries = Seq("fsc", "sbaz", "scala", "scalac", "scaladoc", "scalap")
      binaries foreach { bin =>
        val file = target / dir / (bin + ext)
        val classname = "scala.man1." + bin
        IO.createDirectory(file.getParentFile)
        toError(runner.run(mainClass, Build.data(cp), Seq(classname, file.getAbsolutePath), s.log))
        
      }
    }
  lazy val manmakerSettings: Seq[Setting[_]] = dependentProjectSettings ++ Seq(
    runManmakerMan <<= runManmakerTask(fullClasspath in Runtime, runner in run, "scala.tools.docutil.EmitManPage", "man1", ".1"),
    runManmakerHtml <<= runManmakerTask(fullClasspath in Runtime, runner in run, "scala.tools.docutil.EmitHtml", "html", ".html"),
    ant
  )
  lazy val manmaker = Project("manual", file(".")) settings(manmakerSettings:_*)

  // --------------------------------------------------------------
  //  Packaging a distro
  // --------------------------------------------------------------

  class ScalaToolRunner(classpath: Classpath) {
    lazy val classLoader = new java.net.URLClassLoader(classpath.map(_.data.toURI.toURL).toArray, null)
    lazy val mainClass = classLoader.loadClass("scala.tools.ant.ScalaTool")
    lazy val executeMethod = mainClass.getMethod("execute")
    lazy val setFileMethod = mainClass.getMethod("setFile", classOf[java.io.File])
    lazy val setClassMethod = mainClass.getMethod("setClass", classOf[String])
    lazy val instance = mainClass.newInstance()
    def setClass(cls: String): Unit = setClassMethod.invoke(instance, cls)
    def setFile(file: File): Unit = setFileMethod.invoke(instance, file)
    def execute(): Unit = executeMethod.invoke(instance)
  }

  def genBinTask(classpath: ScopedTask[Classpath], outputDir: ScopedSetting[File]) = (classpath, outputDir) map {
    (cp, outDir) =>
       IO.createDirectory(outDir / "bin")
       for( (cls, dest) <- Map(
             "scala.tools.nsc.MainGenericRunner" -> (outDir / "bin" / "scala"),
             "scala.tools.nsc.Main" -> (outDir / "bin" / "scalac"),
             "scala.tools.nsc.ScalaDoc" -> (outDir / "bin" / "scaladoc"),
             "scala.tools.nsc.CompileClient" -> (outDir / "bin" / "fsc"),
             "scala.tools.scalap.Main" -> (outDir / "bin" / "scalap")
           )) {
         val runner = new ScalaToolRunner(cp)
         runner.setClass(cls)
         runner.setFile(dest)
         runner.execute()
       }  
     // Ouptut a mapping...
     Map(
        outDir / "bin" / "scala" -> "bin/scala",
        outDir / "bin" / "scalac" -> "bin/scalac",
        outDir / "bin" / "scaladoc" -> "bin/scaladoc",
        outDir / "bin" / "scalap" -> "bin/scalap",
        outDir / "bin" / "fsc" -> "bin/fsc"
     )     
  }  
  lazy val genBin = TaskKey[Map[File,String]]("gen-bin", "Creates script files for Scala distribution")
  lazy val scalaDistSettings: Seq[Setting[_]] = Seq(
    crossPaths := false,
    target <<= (baseDirectory, name) apply (_ / "target" / _),
    scalaSource in Compile <<= (baseDirectory, name) apply (_ / "src" / _),
    autoScalaLibrary := false,
    unmanagedJars in Compile := Seq(),
    genBin <<= genBinTask(fullClasspath in quickComp in Runtime, target)
  )
  lazy val scaladist = Project("dist", file(".")) settings(scalaDistSettings:_*)
}