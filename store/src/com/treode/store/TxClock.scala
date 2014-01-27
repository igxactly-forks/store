package com.treode.store

import scala.language.implicitConversions

import com.google.common.primitives.Longs
import com.treode.pickle.Picklers

class TxClock private (val time: Long) extends AnyVal with Ordered [TxClock] {

  def + (n: Int): TxClock = new TxClock (time+n)

  def byteSize: Int = Longs.BYTES

  def compare (that: TxClock): Int =
    this.time compare that.time

  override def toString = "TxClock:%X" format time
}

object TxClock extends Ordering [TxClock] {

  implicit def apply (time: Long): TxClock =
    new TxClock (time)

  val zero = new TxClock (0)

  val max = new TxClock (Long.MaxValue)

  def now = new TxClock (System.currentTimeMillis * 1000)

  def compare (x: TxClock, y: TxClock): Int =
    x compare y

  val pickler = {
    import Picklers._
    wrap (ulong) build (new TxClock (_)) inspect (_.time)
  }}
