/**************************************************************************************
* Copyright (c) 2020 Institute of Computing Technology, CAS
* Copyright (c) 2020 University of Chinese Academy of Sciences
*
* NutShell is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package nutcore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import bus.simplebus._
import bus.axi4._
import utils._
import top.Settings
import rvspeccore.core.RVConfig

trait HasNutCoreParameter {
  // General Parameter for NutShell
  val XLEN = if (Settings.get("IsRV32")) 32 else 64
  val HasMExtension = true
  val HasCExtension = Settings.get("EnableRVC")
  val HasDiv = true
  val HasIcache = Settings.get("HasIcache")
  val HasDcache = Settings.get("HasDcache")
  val HasITLB = Settings.get("HasITLB")
  val HasDTLB = Settings.get("HasDTLB")
  val AddrBits = 64 // AddrBits is used in some cases
  val VAddrBits = if (Settings.get("IsRV32")) 32 else 39 // VAddrBits is Virtual Memory addr bits
  val PAddrBits = 32 // PAddrBits is Phyical Memory addr bits
  val AddrBytes = AddrBits / 8 // unused
  val DataBits = XLEN
  val DataBytes = DataBits / 8
  val EnableVirtualMemory = if (Settings.get("HasDTLB") && Settings.get("HasITLB")) true else false
  val EnablePerfCnt = true
  // Parameter for Argo's OoO backend
  val EnableMultiIssue = Settings.get("EnableMultiIssue")
  val EnableOutOfOrderExec = Settings.get("EnableOutOfOrderExec")
  val EnableMultiCyclePredictor = false // false unless a customized condition branch predictor is included
  val EnableOutOfOrderMemAccess = false // enable out of order mem access will improve OoO backend's performance
}

trait HasNutCoreConst extends HasNutCoreParameter {
  val CacheReadWidth = 8
  val ICacheUserBundleWidth = VAddrBits*2 + 9
  val DCacheUserBundleWidth = 16
  val IndependentBru = if (Settings.get("EnableOutOfOrderExec")) true else false
}

trait HasNutCoreLog { this: RawModule =>
  implicit val moduleName: String = this.name
}

abstract class NutCoreModule extends Module with HasNutCoreParameter with HasNutCoreConst with HasExceptionNO with HasBackendConst with HasNutCoreLog
abstract class NutCoreBundle extends Bundle with HasNutCoreParameter with HasNutCoreConst with HasBackendConst

case class NutCoreConfig (
  FPGAPlatform: Boolean = true,
  Formal: Boolean = Settings.get("Formal"),
  EnableILA: Boolean = Settings.get("EnableILA"),
  EnableDebug: Boolean = Settings.get("EnableDebug"),
  EnhancedLog: Boolean = true ,
  FormalConfig: RVConfig = RVConfig(64, "MCS", "A")
)
// Enable EnhancedLog will slow down simulation,
// but make it possible to control debug log using emu parameter

object AddressSpace extends HasNutCoreParameter {
  // (start, size)
  // address out of MMIO will be considered as DRAM
  def mmio = List(
    (0x30000000L, 0x10000000L),  // internal devices, such as CLINT and PLIC
    (Settings.getLong("MMIOBase"), Settings.getLong("MMIOSize")) // external devices
  )

  def isMMIO(addr: UInt) = mmio.map(range => {
    require(isPow2(range._2))
    val bits = log2Up(range._2)
    (addr ^ range._1.U)(PAddrBits-1, bits) === 0.U
  }).reduce(_ || _)
}

class NutCore(implicit val p: NutCoreConfig) extends NutCoreModule {
  class NutCoreIO extends Bundle {
    val imem = new SimpleBusC
    val dmem = new SimpleBusC
    val mmio = new SimpleBusUC
    val frontend = Flipped(new SimpleBusUC())
  }
  val io = IO(new NutCoreIO)

  // Frontend
  val frontend = (Settings.get("IsRV32"), Settings.get("EnableOutOfOrderExec")) match {
    case (false, false) => Module(new Frontend_inorder)
  }

  // Backend
  if (EnableOutOfOrderExec) {
  } else {
    val backend = Module(new Backend_inorder)

    PipelineVector2Connect(new DecodeIO, frontend.io.out(0), frontend.io.out(1), backend.io.in(0), backend.io.in(1), frontend.io.flushVec(1), 4)

    val mmioXbar = Module(new SimpleBusCrossbarNto1(2))
    val dmemXbar = Module(new SimpleBusCrossbarNto1(4))

    val itlb = EmbeddedTLB(in = frontend.io.imem, mem = dmemXbar.io.in(1), flush = frontend.io.flushVec(0) | frontend.io.bpFlush, csrMMU = backend.io.memMMU.imem, enable = HasITLB)(TLBConfig(name = "itlb", userBits = ICacheUserBundleWidth, totalEntry = 4))
    frontend.io.ipf := itlb.io.ipf
    io.imem <> Cache(in = itlb.io.out, mmio = mmioXbar.io.in.take(1), flush = Fill(2, frontend.io.flushVec(0) | frontend.io.bpFlush), empty = itlb.io.cacheEmpty, enable = HasIcache)(CacheConfig(ro = true, name = "icache", userBits = ICacheUserBundleWidth))

    // dtlb
    val dtlb = EmbeddedTLB(in = backend.io.dmem, mem = dmemXbar.io.in(2), flush = false.B, csrMMU = backend.io.memMMU.dmem, enable = HasDTLB)(TLBConfig(name = "dtlb", totalEntry = 64))
    dmemXbar.io.in(0) <> dtlb.io.out
    io.dmem <> Cache(in = dmemXbar.io.out, mmio = mmioXbar.io.in.drop(1), flush = "b00".U, empty = dtlb.io.cacheEmpty, enable = HasDcache)(CacheConfig(ro = false, name = "dcache"))

    // redirect
    frontend.io.redirect <> backend.io.redirect
    backend.io.flush := frontend.io.flushVec(3,2)

    // Make DMA access through L1 DCache to keep coherence
    dmemXbar.io.in(3) <> io.frontend

    io.mmio <> mmioXbar.io.out

    if (p.FPGAPlatform && p.Formal) {
      val isRead  = RegInit(false.B)
      val isWrite = RegInit(false.B)
      val addr    = RegInit(0.U(39.W))
      val wdata   = RegInit(0.U)
      val width   = RegInit(0.U(log2Ceil(64 + 1).W))

      def sz2wth(size: UInt) = {
        MuxLookup(size, 0.U, List(
          0.U -> 8.U,
          1.U -> 16.U,
          2.U -> 32.U,
          3.U -> 64.U
        ))
      }

      when(backend.io.dmem.isWrite()) {
        isWrite := true.B
        isRead  := false.B
        addr  := backend.io.dmem.req.bits.addr
        wdata := backend.io.dmem.req.bits.wdata
        width := sz2wth(backend.io.dmem.req.bits.size)
      }
      when(backend.io.dmem.isRead()) {
        isRead  := true.B
        isWrite := false.B
        addr  := backend.io.dmem.req.bits.addr
        width := sz2wth(backend.io.dmem.req.bits.size)
      }

      val mem = rvspeccore.checker.ConnectCheckerResult.makeMemSource()(64)

      when(backend.io.dmem.resp.fire) {
        // load or store complete
        when(isRead) {
          isRead       := false.B
          mem.read.valid := true.B
          mem.read.addr  := SignExt(addr, 64)
          mem.read.data  := backend.io.dmem.resp.bits.rdata
          mem.read.memWidth := width
        }.elsewhen(isWrite) {
          isWrite       := false.B
          mem.write.valid := true.B
          mem.write.addr  := SignExt(addr, 64)
          mem.write.data  := wdata
          mem.write.memWidth := width
          // pass addr wdata wmask
        }.otherwise {
          // assert(false.B)
          // may receive some acceptable error resp, but microstructure can handle
        }
      }
    }
  }

  Debug("------------------------ BACKEND ------------------------\n")
}
