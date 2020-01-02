/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.akkastream

import akka.NotUsed
import akka.stream.scaladsl._
import akka.kafka.ConsumerMessage.CommittableOffset

package object scaladsl {
  type FlowWithOffsetContext[-In, +Out] = FlowWithContext[In, CommittableOffset, Out, CommittableOffset, NotUsed]
  type SourceWithOffsetContext[T] = SourceWithContext[T, CommittableOffset, NotUsed]

  object FlowWithOffsetContext {
    /**
     * Creates a [[akka.stream.scaladsl.FlowWithContext FlowWithContext]] that makes it possible for cloudflow to commit reads when
     * `StreamletLogic.atLeastOnceSource` and `StreamletLogic.atLeastOnceSink` is used.
     */
    def apply[In]() = FlowWithContext[In, CommittableOffset]
  }
}
