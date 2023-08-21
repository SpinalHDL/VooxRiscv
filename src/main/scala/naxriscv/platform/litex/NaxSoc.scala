package naxriscv.platform.litex

import naxriscv.debug.EmbeddedJtagPlugin
import naxriscv.platform.NaxriscvTilelink
import naxriscv.utilities.Plugin
import spinal.core._
import spinal.lib._
import spinal.core.fiber._
import spinal.lib.bus.amba4.axi.Axi4SpecRenamer
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SpecRenamer}
import spinal.lib.bus.misc.{OffsetTransformer, OrMapping, SizeMapping}
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.coherent.HubFabric
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.cpu.riscv.debug.DebugModuleFiber
import spinal.lib.misc.plic.TilelinkPlicFiber
import spinal.lib.misc.{InterruptNode, TilelinkFabricClint}
import spinal.lib.system.tag.{MappedNode, MemoryConnection, MemoryTransferTag, MemoryTransfers, PMA}

import scala.collection.mutable.ArrayBuffer

class NaxSocConfig(){
  var naxPlugins : Seq[Seq[Plugin]] = null
  val regions = ArrayBuffer[LitexMemoryRegion]()
  var withJtagTap = false
  var withJtagInstruction = false
  var withDebug = false
}

class NaxSoc(c : NaxSocConfig) extends Component{
  import c._

  val naxes = for(p <- naxPlugins) yield new NaxriscvTilelink().setPlugins(p)
  for(nax <- naxes){
    nax.dBus.setDownConnection(_.connectFrom(_)(a = StreamPipe.HALF, d = StreamPipe.M2S))
    nax.pBus.setDownConnection(_.connectFrom(_)(d = StreamPipe.HALF))
  }

  val memFilter, ioFilter = new fabric.TransferFilter()
  for(nax <- naxes) {
    memFilter.up << List(nax.iBus, nax.dBus)
    ioFilter.up << List(nax.pBus)
  }

  val hub = new HubFabric()
  hub.up << memFilter.down
  hub.up.setUpConnection(_.connectFrom(_)(c = StreamPipe.FULL))

  val toAxi4 = new fabric.Axi4Bridge
  toAxi4.up.forceDataWidth(64)
  toAxi4.down.addTag(PMA.MAIN)
  regions.filter(_.onMemory).foreach(r =>
    toAxi4.up at r.mapping of hub.down
  )

  val peripheral = new Area {
    val bus = Node()
    bus << (hub.down, ioFilter.down)
    bus.setUpConnection(_.connectFrom(_)(d = StreamPipe.M2S))

    val clint = new TilelinkFabricClint()
    clint.node at 0xF0010000l of bus

    val plic = new TilelinkPlicFiber()
    plic.node at 0xF0C00000l of bus

    val externalInterrupts = new Area {
      val port = in Bits (32 bits)
      val toPlic = for (i <- 0 to 31) yield (i != 0) generate new Area {
        val node = plic.createInterruptSlave(i)
        node.withUps = false
        node.flag := port(i)
      }
    }


    for(nax <- naxes) {
      nax.bind(clint)
      nax.bind(plic)
    }

    val axiLiteRegions = regions.filter(e => e.onPeripheral && !e.isIo)
    val toAxiLite4 = new fabric.AxiLite4Bridge
    toAxiLite4.up at(OrMapping(axiLiteRegions.map(_.mapping))) of bus
    toAxiLite4.up.forceDataWidth(32)

    val virtualRegions = for(region <- axiLiteRegions) yield new Area with SpinalTagReady{
      def self = this

      new MemoryConnection {
        override def m = toAxiLite4.down
        override def s = self
        override def transformers = List(OffsetTransformer(region.mapping.lowerBound))
        override def mapping = region.mapping
        override def sToM(downs: MemoryTransfers, args: MappedNode) = downs
        populate()
      }

      addTag(new MemoryTransferTag {
        override def get = toAxiLite4.up.m2s.parameters.emits
      })
      if(region.isCachable) addTag(PMA.MAIN)
    }
  }

  val mBus = Fiber build master(toAxi4.down.pipelined())
  val pBus = Fiber build master(peripheral.toAxiLite4.down.pipelined())

  val debug = c.withDebug generate new Area{
    val cd = ClockDomain.current.copy(reset = in Bool())
    val cdi = c.withJtagInstruction generate ClockDomain.external("jtag_instruction", withReset = false)

    val dm = cd(new DebugModuleFiber())
    naxes.foreach(dm.bindHart)
    val tap = c.withJtagTap generate cd(dm.withJtagTap())
    val instruction = c.withJtagInstruction generate cdi(dm.withJtagInstruction())
  }

  val patcher = Fiber build new Area{
    Axi4SpecRenamer(mBus.get)
    AxiLite4SpecRenamer(pBus.get)

    val i = MemoryConnection.getMemoryTransfers(naxes(0).iBus)
    val d = MemoryConnection.getMemoryTransfers(naxes(0).dBus)
    val p = MemoryConnection.getMemoryTransfers(naxes(0).pBus)


    if (withJtagTap) debug.tap.jtag.setName("jtag")
    if (withJtagInstruction) debug.instruction.setName("jtag_instruction")
    if (c.withDebug) {
      debug.dm.ndmreset.toIo().setName("debug_ndmreset")
      debug.cd.reset.setName("debug_reset")
    }
  }
}


object NaxSoc extends App{
//  SpinalVerilog(new NaxSoc(1))
}