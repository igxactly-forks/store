package com.treode.store.paxos

import scala.util.{Failure, Success}

import com.treode.async.{Async, Callback, Fiber}
import com.treode.cluster.{MessageDescriptor, Peer}
import com.treode.disk.RecordDescriptor
import com.treode.store.{BallotNumber, Bytes, Cell, TimeoutException, TxClock}

import Async.supply
import Callback.ignore

private class Acceptor (val key: Bytes, val time: TxClock, kit: PaxosKit) {
  import Acceptor.{NoPost, Post}
  import kit.{acceptors, archive, cluster, disks, releaser, scheduler}
  import kit.config.{closedLifetime, deliberatingTimeout}

  private val fiber = new Fiber
  private val epoch = releaser.join()
  var state: State = null

  trait State {
    def query (proposer: Peer, ballot: Long, default: Bytes)
    def propose (proposer: Peer, ballot: Long, value: Bytes)
    def choose (chosen: Bytes)
    def checkpoint(): Async [Unit]
  }

  private def panic (s: State, t: Throwable): Unit =
    fiber.execute {
      if (state == s) {
        state = new Panicked (state, t)
        throw t
      }}

  class Opening extends State {

    def query (proposer: Peer, ballot: Long, default: Bytes) {
      val s = new Restoring (default)
      state = s
      s.query (proposer, ballot, default)
      s.restore()
    }

    def propose (proposer: Peer, ballot: Long, value: Bytes) {
      val s = new Restoring (value)
      state = s
      s.propose (proposer, ballot, value)
      s.restore()
    }

    def choose (chosen: Bytes) {
      val s = new Restoring (chosen)
      state = s
      s.choose (chosen)
      s.restore()
    }

    def checkpoint(): Async [Unit] =
      supply()
  }

  class Restoring (default: Bytes) extends State {

    var ballot: BallotNumber = BallotNumber.zero
    var proposal: Proposal = Option.empty
    var proposers = Set.empty [Peer]
    var postable = {_: Deliberating => ()}

    def promise (ballot: BallotNumber, proposal: Proposal, proposer: Peer): Unit =
      postable = (_.open (ballot.number, default, proposer))

    def accept (ballot: BallotNumber, value: Bytes, proposer: Peer): Unit =
      postable = (_.accept (ballot, value, proposer))

    def restore (chosen: Option [Bytes]): Unit =
      fiber.execute {
        if (state == Restoring.this) {
          state = chosen match {
            case Some (value) =>
              Proposer.chosen (key, time, value) (proposers)
              new Closed (value, 0)
            case None =>
              val s = new Deliberating (default, ballot, proposal, proposers)
              postable (s)
              s
          }}}

    def restore() {
      archive.get (key, time) run {
        case Success (Cell (_, found, chosen)) if found == time => restore (chosen)
        case Success (_) => restore (None)
        case Failure (t) => panic (Restoring.this, t)
      }}

    def query (proposer: Peer, _ballot: Long, default: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (this.ballot <= ballot) {
        promise (ballot, proposal, proposer)
        this.ballot = ballot
      }}

    def propose (proposer: Peer, _ballot: Long, value: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (this.ballot <= ballot) {
        accept (ballot, value, proposer)
        this.ballot = ballot
        this.proposal = Some ((ballot, value))
      }}

    def choose (chosen: Bytes) {
      val gen  = archive.put (key, time, chosen)
      state = new Closed (chosen, gen)
      Acceptor.close.record (key, time, chosen, gen) .run (Callback.ignore)
      Proposer.chosen (key, time, chosen) (proposers)
    }

    def checkpoint(): Async [Unit] =
      supply()

    override def toString = s"Acceptor.Restoring($key, $time)"
  }

  class Deliberating (
      val default: Bytes,
      var ballot: BallotNumber,
      var proposal: Proposal,
      var proposers: Set [Peer]) extends State {

    var posting: Post = NoPost
    var postable: Post = NoPost

    def _posted(): Unit =
      fiber.execute {
        if (state == Deliberating.this) {
          posting.reply()
          posting = postable
          postable = NoPost
          posting.record()
        }}

    val posted: Callback [Unit] = {
      case Success (v) => _posted()
      case Failure (t) => panic (Deliberating.this, t)
    }

    def post (post: Post) {
      if (posting == NoPost) {
        posting = post
        posting.record()
      } else {
        this.postable = post
      }}

    fiber.delay (deliberatingTimeout) (timeout())

    def timeout() {
      if (state == Deliberating.this)
        kit.propose (key, time, default) .run {
          case Success (v) => Acceptor.this.choose (v)
          case Failure (_) => timeout()
        }}

    def open (ballot: Long, default: Bytes, proposer: Peer): Unit =
      post (new Post {
        def record = Acceptor.open.record (key, time, default) .run (posted)
        def reply() = Proposer.promise (key, time, ballot, None) (proposer)
      })

    def promise (ballot: BallotNumber, proposal: Proposal, proposer: Peer): Unit =
      post (new Post {
        def record = Acceptor.promise.record (key, time, ballot) .run (posted)
        def reply() = Proposer.promise (key, time, ballot.number, proposal) (proposer)
      })

    def accept (ballot: BallotNumber, value: Bytes, proposer: Peer): Unit =
      post (new Post {
        def record() = Acceptor.accept.record (key, time, ballot, value) .run (posted)
        def reply() = Proposer.accept (key, time, ballot.number) (proposer)
      })

    def reaccept (ballot: BallotNumber, proposer: Peer): Unit =
      post (new Post {
        def record() = Acceptor.reaccept.record (key, time, ballot) .run (posted)
        def reply() = Proposer.accept (key, time, ballot.number) (proposer)
      })

    def query (proposer: Peer, _ballot: Long, default: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (ballot < this.ballot) {
        Proposer.refuse (key, time, this.ballot.number) (proposer)
      } else {
        promise (ballot, proposal, proposer)
        this.ballot = ballot
      }}

    def propose (proposer: Peer, _ballot: Long, value: Bytes) {
      proposers += proposer
      val ballot = BallotNumber (_ballot, proposer.id)
      if (ballot < this.ballot) {
        Proposer.refuse (key, time, this.ballot.number) (proposer)
      } else {
        if (proposal.isDefined && value == proposal.get._2)
          reaccept (ballot, proposer)
        else
          accept (ballot, value, proposer)
        this.ballot = ballot
        this.proposal = Some ((ballot, value))
      }}

    def choose (chosen: Bytes) {
      val gen  = archive.put (key, time, chosen)
      state = new Closed (chosen, gen)
      Acceptor.close.record (key, time, chosen, gen) .run (ignore)
    }

    def checkpoint(): Async [Unit] = {
      proposal match {
        case Some ((ballot, value)) => Acceptor.accept.record (key, time, ballot, value)
        case None => Acceptor.open.record (key, time, default)
      }}

    override def toString = s"Acceptor.Deliberating($key, $time, $default, $ballot, $proposal)"
  }

  class Closed (val chosen: Bytes, gen: Long) extends State {

    releaser.leave (epoch)
    fiber.delay (closedLifetime) (acceptors.remove (key, time, Acceptor.this))

    def query (proposer: Peer, ballot: Long, default: Bytes): Unit =
      Proposer.chosen (key, time, chosen) (proposer)

    def propose (proposer: Peer, ballot: Long, value: Bytes): Unit =
      Proposer.chosen (key, time, chosen) (proposer)

    def choose (chosen: Bytes): Unit =
      require (chosen == this.chosen, "Paxos disagreement")

    def checkpoint(): Async [Unit] =
      supply()

    override def toString = s"Acceptor.Closed($key, $time, $chosen)"
  }

  class Panicked (s: State, thrown: Throwable) extends State {

    releaser.leave (epoch)
    fiber.delay (closedLifetime) (acceptors.remove (key, time, Acceptor.this))

    def query (proposer: Peer, ballot: Long, default: Bytes): Unit = ()

    def propose (proposer: Peer, ballot: Long, value: Bytes): Unit = ()

    def choose (chosen: Bytes): Unit = ()

    def checkpoint(): Async [Unit] = supply()

    override def toString = s"Acceptor.Panicked($key, $time, $thrown)"
  }

  def query (proposer: Peer, ballot: Long, default: Bytes): Unit =
    fiber.execute (state.query (proposer, ballot, default))

  def propose (proposer: Peer, ballot: Long, value: Bytes): Unit =
    fiber.execute (state.propose (proposer, ballot, value))

  def choose (chosen: Bytes): Unit =
    fiber.execute (state.choose (chosen))

  def checkpoint(): Async [Unit] =
    fiber.guard (state.checkpoint())

  override def toString = state.toString
}

private object Acceptor {

  val query = {
    import PaxosPicklers._
    MessageDescriptor (0xFF14D4F00908FB59L, tuple (bytes, txClock, ulong, bytes))
  }

  val propose = {
    import PaxosPicklers._
    MessageDescriptor (0xFF09AFD4F9B688D9L, tuple (bytes, txClock, ulong, bytes))
  }

  val choose = {
    import PaxosPicklers._
    MessageDescriptor (0xFF761FFCDF5DEC8BL, tuple (bytes, txClock, bytes))
  }

  val open = {
    import PaxosPicklers._
    RecordDescriptor (0x77784AB1, tuple (bytes, txClock, bytes))
  }

  val promise = {
    import PaxosPicklers._
    RecordDescriptor (0x32A1544B, tuple (bytes, txClock, ballotNumber))
  }

  val accept = {
    import PaxosPicklers._
    RecordDescriptor (0xD6CCC0BE, tuple (bytes, txClock, ballotNumber, bytes))
  }

  val reaccept = {
    import PaxosPicklers._
    RecordDescriptor (0x52720640, tuple (bytes, txClock, ballotNumber))
  }

  val close = {
    import PaxosPicklers._
    RecordDescriptor (0xAE980885, tuple (bytes, txClock, bytes, long))
  }

  trait Post {
    def record()
    def reply()
  }

  object NoPost extends Post {
    def record() = ()
    def reply() = ()
  }}
