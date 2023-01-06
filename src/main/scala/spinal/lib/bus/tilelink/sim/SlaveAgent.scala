package spinal.lib.bus.tilelink.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

class SlaveAgent(bus : Bus, cd : ClockDomain) {
  val driver = new Area{
    val a = StreamReadyRandomizer(bus.a, cd)
    val b = bus.p.withBCE generate StreamDriver.queue(bus.b, cd)._2
    val c = bus.p.withBCE generate StreamReadyRandomizer(bus.c, cd)
    val d = StreamDriver.queue(bus.d, cd)._2
    val e = bus.p.withBCE generate StreamReadyRandomizer(bus.e, cd)
  }

  def onGet(source : Int,
            address : Long,
            bytes : Int): Unit ={
    ???
  }

  def accessAckData(source : Int,
                    data : Seq[Byte],
                    denied : Boolean = false,
                    corrupt : Boolean = false): Unit ={
    val size = log2Up(data.size)
    for(offset <- 0 until data.size by bus.p.dataBytes){
      driver.d.enqueue { p =>
        val buf = new Array[Byte](bus.p.dataBytes)
        (0 until bus.p.dataBytes).foreach(i => buf(i) = data(offset + i))
        p.opcode  #= Opcode.D.ACCESS_ACK_DATA
        p.param   #= 0
        p.size    #= size
        p.source  #= source
        p.sink    #= 0
        p.denied  #= denied
        p.data    #= buf
        p.corrupt #= corrupt
      }
    }
  }

  val monitor = new Area{
    val a = StreamMonitor(bus.a, cd){ p =>
      val opcode = p.opcode.toEnum
      val source = p.source.toInt
      val address = p.address.toLong
      val size = p.size.toInt
      opcode match {
        case Opcode.A.GET => onGet(source, address, 1 << size)
      }
    }

    val c = bus.p.withBCE generate StreamMonitor(bus.c, cd){ p =>

    }
    val e = bus.p.withBCE generate StreamMonitor(bus.e, cd){ p =>

    }
  }
}
