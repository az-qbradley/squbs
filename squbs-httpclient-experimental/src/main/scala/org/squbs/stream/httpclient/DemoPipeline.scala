/*
 *  Copyright 2015 PayPal
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.squbs.stream.httpclient

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.concurrent.Future

case class DemoPipeline(inbound: Flow[RequestContext, RequestContext, Unit],
                        master: Flow[RequestContext, RequestContext, Unit],
                        outbound: Flow[RequestContext, RequestContext, Unit]) {

  val compositeSink: Sink[RequestContext, Future[HttpResponse]] =
    inbound
      .via(master)
      .via(outbound)
      .map(_.response.getOrElse(HttpResponse(404, entity = "Unknown resource!")))
      .toMat(Sink.head)(Keep.right)

  def run(request: HttpRequest)(implicit materializer: Materializer): Future[HttpResponse] = {
    Source.single(RequestContext(request)).runWith(compositeSink)
  }
}

object DemoPipeline {

  implicit def funcToFlow[In, Out](function: In => Out): Flow[In, Out, Unit] = {
    Flow[In].map(function)
  }

  implicit def asyncFuncToFlow[In, Out](function: In => Future[Out]): Flow[In, Out, Unit] = {
    Flow[In].mapAsync(1)(function)
  }
}
