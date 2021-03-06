package android

import java.io.File

import Keys._
import com.android.SdkConstants
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.repositoryv2.AndroidSdkHandler
import sbt._
import sbt.Keys.onLoad

object AndroidPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val autoImport = android.Keys

  override def buildSettings = Commands.androidCommands

  override def projectConfigurations = AndroidInternal :: Nil

  override def globalSettings = (onLoad := onLoad.value andThen { s =>
    val e = Project.extract(s)

    val androids = e.structure.allProjects map (p => ProjectRef(e.structure.root, p.id)) filter {
      ref => e.getOpt(projectLayout in ref).isDefined
    }
    val androidSet = androids.toSet

    def checkAndroidDependencies(p: ProjectRef): (ProjectRef,Seq[ProjectRef]) = {
      (p,Project.getProject(p, e.structure).toSeq flatMap { prj =>
        val deps = prj.dependencies map (_.project)
        val locals = Project.extract(s).get(localProjects in p).map(
          _.path.getCanonicalPath).toSet
        val depandroids = deps filter (prj => androidSet(prj))
        depandroids filterNot (a => Project.getProject(a, e.structure).exists (d =>
          locals(d.base.getCanonicalPath)))
      })
    }
    def checkForExport(p: ProjectRef): Seq[ProjectRef] = {
      Project.getProject(p, e.structure).toSeq flatMap { prj =>
        val deps = prj.dependencies map (_.project)
        val nonAndroid = deps filterNot (prj => androidSet(prj))

        (deps flatMap checkForExport) ++ (nonAndroid filterNot (d => e.getOpt(sbt.Keys.exportJars in d) exists (_ == true)))
      }
    }
    androids map checkAndroidDependencies foreach { case (p, dep) =>
      dep foreach { d =>
        s.log.warn(s"android: '${p.project}' dependsOn '${d.project}' but does not `buildWith(${d.project})`")
      }
    }
    androids flatMap checkForExport foreach { unexported =>
      s.log.warn(s"${unexported.project} is an Android dependency but does not specify `exportJars := true`")
    }

    val s2 = androids.headOption.fold(s) { a =>
      val s3 = e.runTask(updateCheck in a, s)._1
      e.runTask(updateCheckSdk in a, s3)._1
    }

    androids.foldLeft(s2) { (s, ref) =>
      e.runTask(antLayoutDetector in ref, s)._1
    }
  }) :: Nil

  def sdkPath(slog: sbt.Logger, props: java.util.Properties): String = {
    val cached = SdkLayout.androidHomeCache
    val path = (Option(System getenv "ANDROID_HOME") orElse
      Option(props getProperty "sdk.dir")) flatMap { p =>
      val f = file(p + File.separator)
      if (f.exists && f.isDirectory) {
        cached.getParentFile.mkdirs()
        IO.writeLines(cached, p :: Nil)
        Some(p + File.separator)
      } else None
    } orElse SdkLayout.sdkFallback(cached) getOrElse {
      val home = SdkLayout.fallbackAndroidHome
      slog.info("ANDROID_HOME not set, using " + home.getCanonicalPath)
      home.mkdirs()
      home.getCanonicalPath
    }
    sys.props("com.android.tools.lint.bindir") =
      path + File.separator + SdkConstants.FD_TOOLS
    path
  }

  def sdkManager(path: File, showProgress: Boolean, slog: Logger): AndroidSdkHandler = synchronized {
    AndroidSdkHandler.setRemoteFallback(FallbackSdkLoader)
    val manager = AndroidSdkHandler.getInstance(path)
    val ind = SbtAndroidProgressIndicator(slog)
    val pkgs = retryWhileFailed(
      "Unable to retrieve local packages, retrying...", slog)(
      manager.getSdkManager(ind).getPackages.getLocalPackages)
    if (!pkgs.containsKey("tools")) {
      slog.warn("android sdk tools not found, searching for package...")
      SdkInstaller.installPackage(manager, "", "tools", "android sdk tools", showProgress, slog)
    }
    if (!pkgs.containsKey("platform-tools")) {
      slog.warn("android platform-tools not found, searching for package...")
      SdkInstaller.installPackage(manager,
        "", "platform-tools", "android platform-tools", showProgress, slog)
    }
    manager
  }

  def platformTarget(targetHash: String, sdkHandler: AndroidSdkHandler, showProgress: Boolean, slog: Logger): IAndroidTarget = {
    val manager = sdkHandler.getAndroidTargetManager(SbtAndroidProgressIndicator(slog))
    val ptarget = manager.getTargetFromHashString(targetHash, SbtAndroidProgressIndicator(slog))

    if (ptarget == null) {
      slog.warn(s"platformTarget $targetHash not found, searching for package...")
      SdkInstaller.installPackage(sdkHandler, "platforms;", targetHash, targetHash, showProgress, slog)
    }
    manager.getTargetFromHashString(targetHash, SbtAndroidProgressIndicator(slog))
  }

  def retryWhileFailed[A](err: String, log: Logger, delay: Int = 250)(f: => A): A = {
    Iterator.continually(util.Try(f)).dropWhile { t =>
      val failed = t.isFailure
      if (failed) {
        log.error(err)
        Thread.sleep(delay)
      }
      failed
    }.next.get
  }
}
