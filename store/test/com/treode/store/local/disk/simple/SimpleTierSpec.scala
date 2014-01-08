package com.treode.store.local.disk.simple

import scala.collection.mutable.Builder

import com.treode.async.Callback
import com.treode.pickle.Picklers
import com.treode.store.{Bytes, Cardinals, Fruits, SimpleCell, TxClock}
import com.treode.store.local.disk.DiskSystemStub
import org.scalatest.WordSpec

import Cardinals.One
import Fruits.{AllFruits, Apple, Orange, Watermelon}

class SimpleTierSpec extends WordSpec {

  /** Get the depths of ValueBlocks reached from the index entries. */
  private def getDepths (disk: DiskSystemStub, entries: Iterable [IndexEntry], depth: Int): Set [Int] =
    entries.map (e => getDepths (disk, e.pos, depth+1)) .fold (Set.empty) (_ ++ _)

  /** Get the depths of ValueBlocks for the tree root at `pos`. */
  private def getDepths (disk: DiskSystemStub, pos: Long, depth: Int): Set [Int] = {
    disk.read (pos) match {
      case b: IndexPage => getDepths (disk, b.entries, depth+1)
      case b: CellPage => Set (depth)
    }}

  /** Check that tree rooted at `pos` has all ValueBlocks at the same depth, expect those under
    * the final index entry.
    */
  private def expectBalanced (disk: DiskSystemStub, pos: Long) {
    disk.read (pos) match {
      case b: IndexPage =>
        val ds1 = getDepths (disk, b.entries.take (b.size-1), 1)
        expectResult (1, "Expected lead ValueBlocks at the same depth.") (ds1.size)
        val d = ds1.head
        val ds2 = getDepths (disk, b.last.pos, 1)
        expectResult (true, "Expected final ValueBlocks at depth < $d") (ds2 forall (_ < d))
      case b: CellPage =>
        ()
    }}

  /** Build a tier from fruit. */
  private def buildTier (disk: DiskSystemStub): Long = {
    val builder = new TierBuilder (disk)
    val iter = AllFruits.iterator
    val loop = new Callback [Unit] {
      def pass (v: Unit): Unit =
        if (iter.hasNext)
          builder.add (iter.next, Some (One), this)
      def fail (t: Throwable) = throw t
    }
    loop()
    var pos = 0L
    builder.result (Callback.unary (pos = _))
    pos
  }

  /** Build a sequence of the cells in the tier by using the TierIterator. */
  private def iterateTier (disk: DiskSystemStub, pos: Long): Seq [SimpleCell] = {
    val builder = Seq.newBuilder [SimpleCell]
    TierIterator (disk, pos, Callback.unary { iter: TierIterator =>
      val loop = new Callback [SimpleCell] {
        def pass (e: SimpleCell) {
          builder += e
          if (iter.hasNext)
            iter.next (this)
        }
        def fail (t: Throwable) = throw t
      }
      if (iter.hasNext)
        iter.next (loop)
    })
    builder.result
  }

  private def toSeq (disk: DiskSystemStub, builder: Builder [SimpleCell, _], pos: Long) {
    disk.read (pos) match {
      case page: IndexPage =>
        page.entries foreach (e => toSeq (disk, builder, e.pos))
      case page: CellPage =>
        page.entries foreach (builder += _)
    }}

  /** Build a sequence of the cells in the tier using old-fashioned recursion. */
  private def toSeq (disk: DiskSystemStub, pos: Long): Seq [SimpleCell] = {
    val builder = Seq.newBuilder [SimpleCell]
    toSeq (disk, builder, pos)
    builder.result
  }

  "The simple TierBuilder" should {

    "require that added entries are not duplicated" in {
      val disk = new DiskSystemStub (1 << 16)
      val builder = new TierBuilder (disk)
      builder.add (Apple, None, Callback.unit (()))
      intercept [IllegalArgumentException] {
        builder.add (Apple, None, Callback.unit (()))
      }}

    "require that added entries are sorted by key" in {
      val disk = new DiskSystemStub (1 << 16)
      val builder = new TierBuilder (disk)
      builder.add (Orange, None, Callback.unit (()))
      intercept [IllegalArgumentException] {
        builder.add (Apple, None, Callback.unit (()))
      }}

    "require that added entries are reverse sorted by time" in {
      val disk = new DiskSystemStub (1 << 16)
      val builder = new TierBuilder (disk)
      builder.add (Apple, None, Callback.unit (()))
      intercept [IllegalArgumentException] {
        builder.add (Apple, None, Callback.unit (()))
      }}

    "allow properly sorted entries" in {
      val disk = new DiskSystemStub (1 << 16)
      val builder = new TierBuilder (disk)
      builder.add (Apple, None, Callback.unit (()))
      builder.add (Orange, None, Callback.unit (()))
      builder.add (Watermelon, None, Callback.unit (()))
    }

    "build a blanced tree with all keys" when {

      def checkBuild (maxPageSize: Int) {
        val disk = new DiskSystemStub (maxPageSize)
        val pos = buildTier (disk)
        expectBalanced (disk, pos)
        expectResult (AllFruits.toSeq) (toSeq (disk, pos) .map (_.key))
      }

      "the blocks are limited to one byte" in {
        checkBuild (1)
      }
      "the blocks are limited to 256 bytes" in {
        checkBuild (1 << 6)
      }
      "the blocks are limited to 64K" in {
        checkBuild (1 << 16)
      }}}

  "The simple TierIterator" should {

    "iterate all keys" when {

      def checkIterator (maxPageSize: Int) {
        val disk = new DiskSystemStub (maxPageSize)
        val pos = buildTier (disk)
        expectResult (AllFruits.toSeq) (iterateTier (disk, pos) map (_.key))
      }

      "the blocks are limited to one byte" in {
        checkIterator (1)
      }
      "the blocks are limited to 256 bytes" in {
        checkIterator (1 << 6)
      }
      "the blocks are limited to 64K" in {
        checkIterator (1 << 16)
      }}}

  "The simple Tier" should {

    "find the key" when {

      def checkFind (maxPageSize: Int) {
        val disk = new DiskSystemStub (maxPageSize)
        val pos = buildTier (disk)
        Tier.read (disk, pos, Apple, Callback.unary { cell: Option [SimpleCell] =>
          expectResult (Some (One)) (cell.get.value)
        })
        Tier.read (disk, pos, Orange, Callback.unary { cell: Option [SimpleCell] =>
          expectResult (Some (One)) (cell.get.value)
        })
        Tier.read (disk, pos, Watermelon, Callback.unary { cell: Option [SimpleCell] =>
          expectResult (Some (One)) (cell.get.value)
        })
        Tier.read (disk, pos, One, Callback.unary { cell: Option [SimpleCell] =>
          expectResult (None) (cell)
        })
      }

      "the blocks are limited to one byte" in {
        checkFind (1)
      }
      "the blocks are limited to 256 bytes" in {
        checkFind (1 << 6)
      }
      "the blocks are limited to 64K" in {
        checkFind (1 << 16)
      }}}}
