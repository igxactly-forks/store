package com.treode.disk

import java.util.ArrayList
import scala.collection.JavaConversions._

import com.treode.async.{Callback, callback, delay}
import com.treode.buffer.PagedBuffer

import TagRegistry.Tagger

private class RootRegistry (pages: PageDispatcher) {

  private val checkpoints = new ArrayList [Callback [Tagger] => Unit]

  def checkpoint [B] (desc: RootDescriptor [B]) (f: Callback [B] => Any): Unit =
    synchronized {
      checkpoints.add { cb =>
        f (callback (cb) { root =>
          TagRegistry.tagger (desc.pblk, desc.id.id, root)
        })
      }}

  def checkpoint (gen: Int, cb: Callback [RootRegistry.Meta]) = synchronized {
    val count = checkpoints.size

    val rootsPageWritten = callback (cb) { pos: Position =>
      RootRegistry.Meta (count, pos)
    }

    val rootsWritten = Callback.collect (count, delay (cb) { roots: Seq [Tagger] =>
      pages.write (RootRegistry.page, 0, roots, rootsPageWritten)
    })

    for (cp <- checkpoints)
      cp (rootsWritten)
  }}

private object RootRegistry {

  case class Meta (count: Int, pos: Position)

  object Meta {

    val empty = Meta (0, Position (0, 0, 0))

    val pickler = {
      import DiskPicklers._
      wrap (int, pos)
      .build ((Meta.apply _).tupled)
      .inspect (v => (v.count, v.pos))
    }}


  val page = {
    import DiskPicklers._
    new PageDescriptor (0x6EC7584D, const (0), seq (TagRegistry.pickler))
  }}
