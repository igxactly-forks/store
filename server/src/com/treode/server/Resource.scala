/*
 * Copyright 2014 Treode, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.treode.server

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.treode.async.Async, Async.supply
import com.treode.cluster.HostId
import com.treode.store._
import com.treode.twitter.finagle.http.RichRequest
import com.treode.twitter.util._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.http.path._
import com.twitter.util.Future

import Resource.{Batch, Row, Table, Unmatched, route}

class Resource (host: HostId, store: SchematicStore) extends Service [Request, Response] {

  object KeyParam extends ParamMatcher ("key")

  def read (req: Request, tab: String, key: String): Async [Response] = {
    val rt = req.readTxClock
    val ct = req.conditionTxClock (TxClock.MinValue)
    store.read (tab, key, rt) .map { vs =>
      val v = vs.head
      v.value match {
        case Some (value) if ct < v.time =>
          respond.json (req, v.time, value)
        case Some (_) =>
          respond (req, Status.NotModified)
        case None =>
          respond (req, Status.NotFound)
      }}}

  def scan (req: Request, table: String): Async [Response] = {
    val rt = req.readTxClock
    val ct = req.conditionTxClock (TxClock.MinValue)
    val window = req.window
    val slice = req.slice
    val iter = store
        .scan (table, Bound.firstKey, window, slice)
        .filter (_.value.isDefined)
    supply (respond.json (req, iter))
  }

  def put (req: Request, table: String, key: String): Async [Response] = {
    val tx = req.transactionId (host)
    val ct = req.conditionTxClock (TxClock.now)
    val value = req.readJson [JsonNode]
    store.update (table, key, value, tx, ct)
    .map [Response] { vt =>
      respond.ok (req, vt)
    }
    .recover {
      case exn: StaleException =>
        respond.stale (req, exn.time)
    }}

  def delete (req: Request, table: String, key: String): Async [Response] = {
    val tx = req.transactionId (host)
    val ct = req.conditionTxClock (TxClock.now)
    store.delete (table, key, tx, ct)
    .map [Response] { vt =>
      respond.ok (req, vt)
    }
    .recover {
      case exn: StaleException =>
        respond.stale (req, exn.time)
    }}

  def batch (req: Request): Async [Response] = {
    val tx = req.transactionId (host)
    val ct = req.conditionTxClock (TxClock.now)
    val node = textJson.readTree (req.getContentString)
    store.batch (tx, ct, node)
    .map [Response] { vt =>
      respond.ok (req, vt)
    } .recover {
      case exn: StaleException =>
        respond.stale (req, exn.time)
    }}

  def apply (req: Request): Future [Response] = {
    route (req.path) match {

      case Row (tab, key) =>
        req.method match {
          case Method.Get =>
            read (req, tab, key) .toTwitterFuture
          case Method.Put =>
            put (req, tab, key) .toTwitterFuture
          case Method.Delete =>
            delete (req, tab, key) .toTwitterFuture
          case _ =>
            Future.value (respond (req, Status.MethodNotAllowed))
        }

      case Table (tab) =>
        req.method match {
          case Method.Get =>
            scan (req, tab) .toTwitterFuture
          case _ =>
            Future.value (respond (req, Status.MethodNotAllowed))
        }

      case Batch =>
        req.method match {
          case Method.Post =>
            batch (req) .toTwitterFuture
          case _ =>
            Future.value (respond (req, Status.MethodNotAllowed))
        }

      case Unmatched =>
        Future.value (respond (req, Status.NotFound))
    }}}

object Resource {

  private val _route = """/([\p{Graph}&&[^/]]+)(/([\p{Graph}&&[^/]]+)?)?""".r

  sealed abstract class Route
  case class Table (table: String) extends Route
  case class Row (table: String, key: String) extends Route
  case object Batch extends Route
  case object Unmatched extends Route

  def route (path: String): Route =
    path match {
      case _route ("batch-write", _, null) =>
        Batch
      case _route (tab, _, null) =>
        Table (tab)
      case _route (tab, _, key) =>
        Row (tab, key)
      case _ =>
        Unmatched
    }}
