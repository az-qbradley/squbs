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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import org.scalatest.{FlatSpecLike, Matchers}
import org.squbs.stream.httpclient.DemoPipeline._
import org.squbs.stream.httpclient.RequestContext._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class DemoPipelineSpec extends TestKit(ActorSystem("RequestContextSpecSys")) with FlatSpecLike with Matchers {

  import system.dispatcher

  implicit val fm = ActorMaterializer()

  val syncInbound: RequestContext => RequestContext =
    ctx => ctx.withAttributes("key1" -> "value1").addRequestHeaders(RawHeader("reqHeader", "reqHeaderValue"))

  val badInbound: RequestContext => RequestContext =
    ctx => throw new IllegalArgumentException("BadMan")

  val syncOutbound: RequestContext => RequestContext =
    ctx => {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ attributes2Headers(ctx.attributes) ++ ctx.request.headers))
      ctx.copy(response = newResp)
    }

  val masterAction: HttpRequest => Future[HttpResponse] =
    req => Future.successful(HttpResponse(entity = "HelloWorld"))

  val masterFlow: Flow[RequestContext, RequestContext, Unit] =
    Flow[RequestContext].mapAsync(1) {
      ctx => masterAction(ctx.request).map(resp => ctx.copy(response = Option(resp)))
    }

  "Simple flow" should "work" in {

    val pipeline = DemoPipeline(syncInbound, masterFlow, syncOutbound)
    val resp = pipeline.run(HttpRequest())
    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "key1").isDefined should be(true)
    result.headers.find(_.name == "reqHeader").isDefined should be(true)
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }
}
