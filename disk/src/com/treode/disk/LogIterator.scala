package com.treode.disk

import java.nio.file.Path
import java.util.ArrayDeque

import com.treode.async._
import com.treode.async.io.File
import com.treode.buffer.PagedBuffer

import RecordHeader.{Entry, LogAlloc, LogEnd, PageAlloc, PageWrite}

private class LogIterator private (
    path: Path,
    file: File,
    buf: PagedBuffer,
    superb: SuperBlock,
    alloc: SegmentAllocator,
    records: RecordRegistry,
    logSegs: ArrayDeque [Int],
    private var logSeg: SegmentBounds,
    private var pageSeg: SegmentBounds,
    private var pageLedger: PageLedger
) (
    implicit scheduler: Scheduler
) extends AsyncIterator [(Long, Unit => Any)] {

  private var logPos = superb.logHead
  private var pagePos = superb.pagePos

  private def failed [A] (cb: Callback [A], t: Throwable) {
    logPos = -1L
    cb.fail (t)
  }

  def hasNext: Boolean = logPos > 0

  private def frameRead (cb: Callback [(Long, Unit => Any)]) =
    new Callback [Int] {

      def pass (len: Int) {
        val start = buf.readPos
        val hdr = RecordHeader.pickler.unpickle (buf)
        hdr match {
          case LogEnd =>
            logPos = -1L
            buf.clear()
            cb (Long.MaxValue, _ => ())

          case LogAlloc (next) =>
            logSeg = alloc.allocSeg (next)
            logSegs.add (logSeg.num)
            logPos = logSeg.pos
            buf.clear()
            file.deframe (buf, logPos, this)

          case PageWrite (pos, _ledger) =>
            pagePos = pos
            pageLedger.add (_ledger)
            logPos += len
            file.deframe (buf, logPos, this)

          case PageAlloc (next, _ledger) =>
            pageSeg = alloc.allocSeg (next)
            pagePos = pageSeg.pos
            pageLedger = _ledger.unzip
            logPos += len
            file.deframe (buf, logPos, this)

          case Entry (time, id) =>
            val end = buf.readPos
            val entry = records.read (id.id, buf, len - end + start)
            logPos += len
            cb (time, entry)

          case _ =>
            cb.fail (new MatchError)
        }}

      def fail (t: Throwable) = failed (cb, t)
    }

  def next (cb: Callback [(Long, Unit => Any)]): Unit =
    file.deframe (buf, logPos, frameRead (cb))

  def close (logd: LogDispatcher, paged: PageDispatcher): DiskDrive = {
    val disk =
      new DiskDrive (superb.id, path, file, superb.config, alloc, logd, logSegs, superb.logHead,
          logPos, pageSeg, superb.pagePos, pageLedger)
    val logw = new LogWriter (disk, logd, buf, logSeg, logPos)
    logd.engage (logw)
    val pagew = new PageWriter (disk, paged, pageSeg, pagePos, pageLedger)
    disk
  }}

object LogIterator {

  def apply (
      path: Path,
      file: File,
      superb: SuperBlock,
      records: RecordRegistry,
      cb: Callback [(Int, LogIterator)]) (
          implicit scheduler: Scheduler): Unit =

    guard (cb) {
      val alloc = SegmentAllocator.recover (superb.config, superb.free)
      val logSeg = alloc.allocSeg (superb.logSeg)
      val logSegs = new ArrayDeque [Int]
      logSegs.add (logSeg.num)
      val pageSeg = alloc.allocSeg (superb.pageSeg)
      val buf = PagedBuffer (12)
      PageLedger.read (file, pageSeg.pos, delay (cb) { ledger =>
        file.fill (buf, superb.logHead, 1, callback (cb) { _ =>
          val iter = new LogIterator (path, file, buf, superb, alloc, records, logSegs, logSeg,
              pageSeg, ledger)
          (superb.id, iter)
        })
      })
    }

  def replay (
      useGen1: Boolean,
      reads: Seq [SuperBlocks],
      records: RecordRegistry,
      cb: Callback [DiskDrives]) (
          implicit scheduler: Scheduler): Unit =
    guard (cb) {

      def replayed (logs: Map [Int, LogIterator]) = callback (cb) { _: Unit =>
        val logd = new LogDispatcher
        val paged = new PageDispatcher
        val disks =
          for (read <- reads) yield {
            val superb = read.superb (useGen1)
            logs (superb.id) .close (logd, paged)
          }
        new DiskDrives (logd, paged, disks.mapBy (_.id))
      }

      def merged (logs: Map [Int, LogIterator]) = delay (cb) { iter: ReplayIterator =>
        AsyncIterator.foreach (iter, replayed (logs)) { case ((time, replay), cb) =>
          guard (cb) (replay())
          cb()
        }}

      val ordering = Ordering.by [(Long, Unit => Any), Long] (_._1)

      val allMade = delay (cb) { logs: Map [Int, LogIterator] =>
        AsyncIterator.merge (logs.values.iterator, merged (logs)) (ordering)
      }

      val oneMade = Callback.map (reads.size, allMade)
      reads foreach { read =>
        val superb =
        apply (read.path, read.file, read.superb (useGen1), records, oneMade)
      }}}
