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

// Out Of Order Load/Store Unit

object LSUOpType { //TODO: refactor LSU fuop
  def lb   = "b0000000".U
  def lh   = "b0000001".U
  def lw   = "b0000010".U
  def ld   = "b0000011".U
  def lbu  = "b0000100".U
  def lhu  = "b0000101".U
  def lwu  = "b0000110".U
  def sb   = "b0001000".U
  def sh   = "b0001001".U
  def sw   = "b0001010".U
  def sd   = "b0001011".U

  def lr      = "b0100000".U
  def sc      = "b0100001".U
  def amoswap = "b0100010".U
  def amoadd  = "b1100011".U
  def amoxor  = "b0100100".U
  def amoand  = "b0100101".U
  def amoor   = "b0100110".U
  def amomin  = "b0110111".U
  def amomax  = "b0110000".U
  def amominu = "b0110001".U
  def amomaxu = "b0110010".U
  
  def isAdd(func: UInt) = func(6)
  def isAtom(func: UInt): Bool = func(5)
  def isStore(func: UInt): Bool = func(3)
  def isLoad(func: UInt): Bool = !isStore(func) & !isAtom(func)
  def isLR(func: UInt): Bool = func === lr
  def isSC(func: UInt): Bool = func === sc
  def isAMO(func: UInt): Bool = isAtom(func) && !isLR(func) && !isSC(func)

  def needMemRead(func: UInt): Bool = isLoad(func) || isAMO(func) || isLR(func)
  def needMemWrite(func: UInt): Bool = isStore(func) || isAMO(func) || isSC(func)

  def atomW = "010".U
  def atomD = "011".U
}

object MEMOpID {
  def idle   = "b0000_000".U
  def load   = "b0001_001".U
  def store  = "b0001_010".U
  def storec = "b0010_010".U //store commit
  def amo    = "b0001_111".U
  def lr     = "b0001_101".U
  def sc     = "b0001_110".U
  def tlb    = "b0100_001".U
  def vload  = "b1000_001".U
  def vstore = "b1000_010".U

  def needLoad(memop: UInt) = memop(0)
  def needStore(memop: UInt) = memop(1)
  def needAlu(memop: UInt) = memop(2)
  def commitToCDB(memop: UInt) = memop(3)
  def commitToSTQ(memop: UInt) = memop(4)
  def commitToTLB(memop: UInt) = memop(5)
  def commitToVPU(memop: UInt) = memop(6)
}

trait HasLSUConst {
  val IndependentAddrCalcState = false
  val moqSize = 8
  val storeQueueSize = 8
}


class StoreQueueEntry extends NutCoreBundle{
  val pc       = UInt(VAddrBits.W)
  val prfidx   = UInt(prfAddrWidth.W) // for debug
  val brMask   = UInt(checkpointSize.W)
  val wmask    = UInt((XLEN/8).W) // for store queue forwarding
  val vaddr    = UInt(VAddrBits.W)
  val paddr    = UInt(PAddrBits.W)
  val func     = UInt(7.W)
  val size     = UInt(2.W)
  val op       = UInt(7.W)
  val data     = UInt(XLEN.W)
  val isMMIO   = Bool()
  val valid    = Bool()
}

class moqEntry extends NutCoreBundle{
  val pc       = UInt(VAddrBits.W)
  val isRVC    = Bool()
  val prfidx   = UInt(prfAddrWidth.W)
  val brMask   = UInt(checkpointSize.W)
  val stMask   = UInt(robSize.W)
  val vaddr    = UInt(VAddrBits.W) // for debug
  val paddr    = UInt(PAddrBits.W)
  val func     = UInt(7.W)
  val size     = UInt(2.W)
  val op       = UInt(7.W)
  val data     = UInt(XLEN.W)
  val fdata    = UInt(XLEN.W) // forwarding data
  val fmask    = UInt((XLEN/8).W) // forwarding mask
  val asrc     = UInt(XLEN.W) // alusrc2 for atom inst
  val rfWen    = Bool()
  val isMMIO   = Bool()
  val valid    = Bool()
  val tlbfin   = Bool()
  val finished = Bool()
  val rollback = Bool()
  val loadPageFault  = Bool()
  val storePageFault  = Bool()
  val loadAddrMisaligned  = Bool()
  val storeAddrMisaligned = Bool()
}

class DCacheUserBundle extends NutCoreBundle {
  val moqidx   = UInt(5.W) //TODO
  val op       = UInt(7.W)
}

class MemReq extends NutCoreBundle {
  val addr = UInt(VAddrBits.W)
  val size = UInt(2.W)
  val wdata = UInt(XLEN.W)
  val wmask = UInt(8.W)
  val cmd = UInt(4.W)
  val user = new DCacheUserBundle
  val valid = Bool()
}

class AtomALU extends NutCoreModule {
  val io = IO(new NutCoreBundle{
    val src1 = Input(UInt(XLEN.W))
    val src2 = Input(UInt(XLEN.W))
    val func = Input(UInt(7.W))
    val isWordOp = Input(Bool())
    val result = Output(UInt(XLEN.W))
  })

  // src1: load result
  // src2: reg  result
  val src1 = io.src1
  val src2 = io.src2
  val func = io.func
  val isAdderSub = !LSUOpType.isAdd(func) 
  val adderRes = (src1 +& (src2 ^ Fill(XLEN, isAdderSub))) + isAdderSub
  val xorRes = src1 ^ src2
  val sltu = !adderRes(XLEN)
  val slt = xorRes(XLEN-1) ^ sltu

  val res = LookupTreeDefault(func(5, 0), adderRes, List(
    LSUOpType.amoswap -> src2,
    // LSUOpType.amoadd  -> adderRes,
    LSUOpType.amoxor  -> xorRes,
    LSUOpType.amoand  -> (src1 & src2),
    LSUOpType.amoor   -> (src1 | src2),
    LSUOpType.amomin  -> Mux(slt(0), src1, src2),
    LSUOpType.amomax  -> Mux(slt(0), src2, src1),
    LSUOpType.amominu -> Mux(sltu(0), src1, src2),
    LSUOpType.amomaxu -> Mux(sltu(0), src2, src1)
  ))

  io.result :=  Mux(io.isWordOp, SignExt(res(31,0), 64), res(XLEN-1,0))
}
