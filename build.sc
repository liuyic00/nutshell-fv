import mill._, scalalib._
import coursier.maven.MavenRepository

trait CommonModule extends ScalaModule {
  override def scalaVersion = "2.12.13"

  override def scalacOptions = Seq("-Xsource:2.11")
}

trait HasXsource211 extends ScalaModule {
  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-language:reflectiveCalls",
      "-Xsource:2.11"
    )
  }
}

trait HasChisel3 extends ScalaModule {
  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots")
    )
  }
  override def ivyDeps = Agg(
    ivy"edu.berkeley.cs::chisel3:3.5.4",
    ivy"cn.ac.ios.tis::riscvspeccore:0.1.0"
  )
  override def scalacPluginIvyDeps = Agg(
    ivy"edu.berkeley.cs:::chisel3-plugin:3.5.4",
    ivy"org.scalamacros:::paradise:2.1.1"
  )
}

trait HasChiselTests extends CrossSbtModule {
  object test extends Tests with TestModule.ScalaTest{
    override def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.0.4",
      ivy"edu.berkeley.cs::chiseltest:0.5.4"
    )
  }
}

object difftest extends SbtModule with CommonModule with HasChisel3 {
  override def millSourcePath = os.pwd / "difftest"
}

object chiselModule extends CrossSbtModule with HasChisel3 with HasChiselTests with HasXsource211 {
  def crossScalaVersion = "2.12.13"
  override def moduleDeps = super.moduleDeps ++ Seq(
    difftest
  )
}
