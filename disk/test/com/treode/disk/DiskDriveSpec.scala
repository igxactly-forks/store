package com.treode.disk

import java.nio.file.Paths

import com.treode.async.Async
import com.treode.async.io.File
import com.treode.async.io.stubs.StubFile
import com.treode.async.implicits._
import com.treode.async.stubs.{CallbackCaptor, StubScheduler}
import com.treode.async.stubs.implicits._
import com.treode.buffer.PagedBuffer
import com.treode.pickle.Picklers
import org.scalatest.FreeSpec

import DiskTestTools._

class DiskDriveSpec extends FreeSpec {

  class DistinguishedException extends Exception

  implicit val config = DiskTestConfig()

  private def init (file: File, kit: DiskKit) = {
    val path = Paths.get ("a")
    val free = IntSet()
    val boot = BootBlock (sysid, 0, 0, Set (path))
    val geom = DiskGeometry.test()
    new SuperBlock (0, boot, geom, false, free, 0)
    DiskDrive.init (0, path, file, geom, boot, kit)
  }

  "DiskDrive.init should" - {

    "work when all is well" in {
      implicit val scheduler = StubScheduler.random()
      val file = StubFile()
      val kit = new DiskKit (sysid, 0)
      val drive = init (file, kit) .pass
    }

    "issue three writes to the disk" in {
      implicit val scheduler = StubScheduler.random()
      val file = StubFile()
      val kit = new DiskKit (sysid, 0)
      file.stop = true
      val cb = init (file, kit) .capture()
      scheduler.run()
      file.last.pass()
      file.last.pass()
      file.last.pass()
      file.stop = false
      scheduler.run()
      cb.passed
    }

    "fail when it cannot write the superblock" in {
      implicit val scheduler = StubScheduler.random()
      val file = StubFile()
      val kit = new DiskKit (sysid, 0)
      file.stop = true
      val cb = init (file, kit) .capture()
      scheduler.run()
      file.last.pass()
      file.last.fail (new DistinguishedException)
      file.last.pass()
      file.stop = false
      scheduler.run()
      cb.failed [DistinguishedException]
    }

    "fail when it cannot clear the next superblock" in {
      implicit val scheduler = StubScheduler.random()
      val file = StubFile()
      val kit = new DiskKit (sysid, 0)
      file.stop = true
      val cb = init (file, kit) .capture()
      scheduler.run()
      file.last.pass()
      file.last.pass()
      file.last.fail (new DistinguishedException)
      file.stop = false
      scheduler.run()
      cb.failed [DistinguishedException]
    }

    "fail when it cannot write the log tail" in {
      implicit val scheduler = StubScheduler.random()
      val file = StubFile()
      val kit = new DiskKit (sysid, 0)
      file.stop = true
      val cb = init (file, kit) .capture()
      scheduler.run()
      file.last.fail (new DistinguishedException)
      file.last.pass()
      file.last.pass()
      file.stop = false
      scheduler.run()
      cb.failed [DistinguishedException]
    }}}
