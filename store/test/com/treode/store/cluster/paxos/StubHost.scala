package com.treode.store.cluster.paxos

import java.nio.file.Paths
import com.treode.async.Callback
import com.treode.async.io.StubFile
import com.treode.cluster.{BaseStubHost, HostId, StubCluster}
import com.treode.disk.{Disks, DiskDriveConfig}

private class StubHost (id: HostId, cluster: StubCluster) extends BaseStubHost (id, cluster) {

  val disks = Disks (cluster.scheduler)
  val file = new StubFile (cluster.scheduler)
  val config = DiskDriveConfig (16, 8, 1L<<20)
  disks.attach (Seq ((Paths.get ("a"), file, config)), Callback.ignore)

  val paxos = new PaxosKit () (cluster.random, cluster.scheduler, this, disks)
}
