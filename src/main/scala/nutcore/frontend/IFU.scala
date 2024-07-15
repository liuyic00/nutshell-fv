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

import utils._
import bus.simplebus._
import top.Settings

trait HasResetVector {
  val resetVector = Settings.getLong("ResetVector")
}

class ICacheUserBundle extends NutCoreBundle {
    val pc = UInt(VAddrBits.W)
    val brIdx = UInt(4.W) // mark if an inst is predicted to branch
    val pnpc = UInt(VAddrBits.W)
    val instValid = UInt(4.W) // mark which part of this inst line is valid
}
// Note: update ICacheUserBundleWidth when change ICacheUserBundle


class IFU_inorder extends NutCoreModule with HasResetVector {
  val io = IO(new Bundle {

    val imem = new SimpleBusUC(userBits = VAddrBits*2 + 4, addrBits = VAddrBits)
    val out = Decoupled(new CtrlFlowIO)

    val redirect = Flipped(new RedirectIO)
    val flushVec = Output(UInt(4.W))
    val bpFlush = Output(Bool())
    val ipf = Input(Bool())
  })

  // pc
  val pc = RegInit(resetVector.U(VAddrBits.W))
  val pcUpdate = io.redirect.valid || io.imem.req.fire
  val snpc = Mux(pc(1), pc + 2.U, pc + 4.U)  // sequential next pc

  val bp1 = Module(new BPU_inorder)

  val crosslineJump = bp1.io.crosslineJump
  val crosslineJumpLatch = RegInit(false.B) 
  when(pcUpdate || bp1.io.flush) {
    crosslineJumpLatch := Mux(bp1.io.flush, false.B, crosslineJump && !crosslineJumpLatch)
  }
  val crosslineJumpTarget = RegEnable(bp1.io.out.target, crosslineJump)
  val crosslineJumpForceSeq = crosslineJump && bp1.io.out.valid
  val crosslineJumpForceTgt = crosslineJumpLatch && !bp1.io.flush

  // predicted next pc
  val pnpc = Mux(crosslineJump, snpc, bp1.io.out.target)
  val pbrIdx = bp1.io.brIdx
  val npc = Mux(io.redirect.valid, io.redirect.target, Mux(crosslineJumpLatch, crosslineJumpTarget, Mux(bp1.io.out.valid, pnpc, snpc)))
  val npcIsSeq = Mux(io.redirect.valid , false.B, Mux(crosslineJumpLatch, false.B, Mux(crosslineJump, true.B, Mux(bp1.io.out.valid, false.B, true.B))))
  // Debug("[NPC] %x %x %x %x %x %x\n",crosslineJumpLatch, crosslineJumpTarget, crosslineJump, bp1.io.out.valid, pnpc, snpc)

  // val npc = Mux(io.redirect.valid, io.redirect.target, Mux(io.redirectRVC.valid, io.redirectRVC.target, snpc))
  val brIdx = Wire(UInt(4.W)) 
  // brIdx(0) -> branch at pc offset 0 (mod 4)
  // brIdx(1) -> branch at pc offset 2 (mod 4)
  // brIdx(2) -> branch at pc offset 6 (mod 8), and this inst is not rvc inst
  brIdx := Cat(npcIsSeq, Mux(io.redirect.valid, 0.U, pbrIdx))
  //TODO: BP will be disabled shortly after a redirect request

  bp1.io.in.pc.valid := io.imem.req.fire // only predict when Icache accepts a request
  bp1.io.in.pc.bits := npc  // predict one cycle early

  // Debug(bp1.io.in.pc.valid, p"pc: ${Hexadecimal(pc)} npc: ${Hexadecimal(npc)}\n")
  // Debug(bp1.io.out.valid, p"valid!!\n")

  bp1.io.flush := io.redirect.valid

  when (pcUpdate) { 
    pc := npc 
    // printf("[IF1] pc=%x\n", pc)
  }

  Debug(pcUpdate, "[IFUPC] pc:%x pcUpdate:%d npc:%x RedValid:%d RedTarget:%x LJL:%d LJTarget:%x LJ:%d snpc:%x bpValid:%d pnpn:%x \n",pc, pcUpdate, npc, io.redirect.valid,io.redirect.target,crosslineJumpLatch,crosslineJumpTarget,crosslineJump,snpc,bp1.io.out.valid,pnpc)

  io.flushVec := Mux(io.redirect.valid, "b1111".U, 0.U)
  io.bpFlush := false.B

  io.imem.req.bits.apply(addr = Cat(pc(VAddrBits-1,1),0.U(1.W)), //cache will treat it as Cat(pc(63,3),0.U(3.W))
    size = "b11".U, cmd = SimpleBusCmd.read, wdata = 0.U, wmask = 0.U, user = Cat(brIdx(3,0), npc(VAddrBits-1, 0), pc(VAddrBits-1, 0)))
  io.imem.req.valid := io.out.ready
  //TODO: add ctrlFlow.exceptionVec
  io.imem.resp.ready := io.out.ready || io.flushVec(0)

  io.out.bits := DontCare
    //inst path only uses 32bit inst, get the right inst according to pc(2)

  Debug(io.imem.req.fire, "[IFI] pc=%x user=%x %x %x %x \n", io.imem.req.bits.addr, io.imem.req.bits.user.getOrElse(0.U), io.redirect.valid, pbrIdx, brIdx)
  Debug(io.out.fire, "[IFO] pc=%x inst=%x\n", io.out.bits.pc, io.out.bits.instr)

  // io.out.bits.instr := (if (XLEN == 64) io.imem.resp.bits.rdata.asTypeOf(Vec(2, UInt(32.W)))(io.out.bits.pc(2))
                      //  else io.imem.resp.bits.rdata)
  io.out.bits.instr := io.imem.resp.bits.rdata
  io.imem.resp.bits.user.map{ case x =>
    io.out.bits.pc := x(VAddrBits-1,0)
    io.out.bits.pnpc := x(VAddrBits*2-1,VAddrBits)
    io.out.bits.brIdx := x(VAddrBits*2 + 3, VAddrBits*2)
  }
  io.out.bits.exceptionVec(instrPageFault) := io.ipf
  io.out.valid := io.imem.resp.valid && !io.flushVec(0)

  BoringUtils.addSource(BoolStopWatch(io.imem.req.valid, io.imem.resp.fire), "perfCntCondMimemStall")
  BoringUtils.addSource(io.flushVec.orR, "perfCntCondMifuFlush")
}