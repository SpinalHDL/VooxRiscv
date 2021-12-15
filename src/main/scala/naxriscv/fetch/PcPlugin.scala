package naxriscv.fetch

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import naxriscv.interfaces.{AddressTranslationService, InitCycles, JumpCmd, JumpService}
import spinal.lib.pipeline._
import naxriscv.utilities.Plugin
import naxriscv._
import naxriscv.Global._
import naxriscv.fetch._
import naxriscv.Fetch._

import scala.collection.mutable.ArrayBuffer
import Frontend._


class PcPlugin(resetVector : BigInt = 0x80000000l) extends Plugin with JumpService{
  case class JumpInfo(interface :  Flow[JumpCmd], priority : Int)
  val jumpInfos = ArrayBuffer[JumpInfo]()
  override def createJumpInterface(priority : Int): Flow[JumpCmd] = {
    val interface = Flow(JumpCmd(widthOf(getService[AddressTranslationService].PC)))
    jumpInfos += JumpInfo(interface, priority)
    interface
  }

  val setup = create early new Area{
    val pipeline = getService[FetchPlugin]
    pipeline.lock.retain()
  }

  val logic = create late new Area{
    val PC = getService[AddressTranslationService].PC
    val stage = setup.pipeline.getStage(0)
    val fetch = getService[FetchPlugin]
    val pipeline = fetch.getPipeline()
    import stage._

    val sliceRangeLow = if (RVC) 1 else 2
    val sliceRange = (sliceRangeLow + log2Up(SLICE_COUNT) - 1 downto sliceRangeLow)

    val jump = new Area {
      val sortedByStage = jumpInfos.sortWith(_.priority > _.priority)
      val valids = sortedByStage.map(_.interface.valid)
      val cmds = sortedByStage.map(_.interface.payload)

      val pcLoad = Flow(JumpCmd(pcWidth = widthOf(PC)))
      pcLoad.valid := jumpInfos.map(_.interface.valid).orR
      if(valids.nonEmpty) {
        pcLoad.payload := MuxOH(OHMasking.first(valids.asBits), cmds)
      } else {
        pcLoad.payload.assignDontCare()
      }
    }

    val init = new Area{
      val requests = getServicesOf[InitCycles]
      val request = (0 +: requests.map(_.initCycles)).max
      val counter = Reg(UInt(log2Up(request) + 1 bits)) init(0)
      val booted = counter.msb
      counter := counter + U(!booted)
    }

    val fetchPc = new Area{
      //PC calculation without Jump
      val output = Stream(UInt(32 bits))
      val pcReg = Reg(UInt(32 bits)) init(resetVector) addAttribute(Verilator.public)
      val correction = False
      val correctionReg = RegInit(False) setWhen(correction) clearWhen(output.fire)
      val corrected = correction || correctionReg
      val pcRegPropagate = False
      val inc = RegInit(False) clearWhen(correction || pcRegPropagate) setWhen(output.fire) clearWhen(!output.valid && output.ready)
      val pc = pcReg + (U(inc) << sliceRange.high+1)


      val flushed = False

      if(RVC) when(inc) {
        pc(sliceRange) := 0
      }

      when(jump.pcLoad.valid) {
        correction := True
        pc := jump.pcLoad.pc
        flushed := True
      }

      when(init.booted && (output.ready || correction || pcRegPropagate)){
        pcReg := pc
      }

      pc(0) := False
      if(!RVC) pc(1) := False

      val fetcherHalt = False
      output.valid := !fetcherHalt && init.booted
      output.payload := pc
    }

    fetchPc.output.ready := stage.isReady
    stage.valid := fetchPc.output.valid
    stage(fetch.keys.FETCH_PC_PRE_TRANSLATION) := fetchPc.output.payload

    setup.pipeline.lock.release()
  }
}