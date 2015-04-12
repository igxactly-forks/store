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

package com.treode.async

import scala.collection.SortedMap

private class ArrayLatch [A] (cb: Callback [Seq [A]]) (implicit manifest: Manifest [A])
extends AbstractLatch [(Int, A), Seq [A]] (cb) {

  private val values = SortedMap.newBuilder [Int, A]

  protected def result = values.result.values.toSeq

  protected def add (v: (Int, A)): Unit =
    values += v
}