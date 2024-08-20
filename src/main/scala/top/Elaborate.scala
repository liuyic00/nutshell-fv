package top

import nutcore._

object EmitNutCore extends App {
  // config
  val s = (FormalSettings()) ++ (InOrderSettings()) ++ Map("Formal" -> false, "RVFI" -> true)
  s.foreach { Settings.settings += _ }
  Settings.settings.toList.sortBy(_._1)(Ordering.String).foreach {
    case (f, v: Long) =>
      println(f + " = 0x" + v.toHexString)
    case (f, v) =>
      println(f + " = " + v)
  }

  (new chisel3.stage.ChiselStage)
    .emitSystemVerilog(new NutCore()(NutCoreConfig()), Array("--target-dir", "test_run_dir/Elaborate"))
}
