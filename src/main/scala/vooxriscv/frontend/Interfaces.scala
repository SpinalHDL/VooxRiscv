package vooxriscv.frontend

import spinal.core._
import vooxriscv.Global
import vooxriscv.pipeline._
import vooxriscv.utilities.JunctionKey



object Frontend extends Area{
  this.setName("FETCH")

  val RVC = ScopeProperty[Boolean]
  val FETCH_DATA_WIDTH = ScopeProperty[Int]
  val INSTRUCTION_WIDTH = ScopeProperty[Int]
  val BRANCH_HISTORY_WIDTH = ScopeProperty[Int]
  val DECODE_COUNT = ScopeProperty[Int]
  def FETCH_COUNT = DECODE_COUNT.get

  def SLICE_WIDTH = if(RVC) 16 else 32
  def SLICE_BYTES = if(RVC) 2 else 4
  def SLICE_COUNT = FETCH_DATA_WIDTH/SLICE_WIDTH

  val WORD = Stageable(Bits(FETCH_DATA_WIDTH bits))
  val MASK = Stageable(Bits(FETCH_DATA_WIDTH/SLICE_WIDTH bits))


  val ALIGNED_INSTRUCTION = Stageable(Vec.fill(DECODE_COUNT)(Bits(INSTRUCTION_WIDTH bits)))
  val BRANCH_HISTORY = Stageable(Bits(BRANCH_HISTORY_WIDTH bits))



  val WORD_JUNCTION = new JunctionKey()
  val INSTRUCTION_JUNCTION = new JunctionKey()
}
