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
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.TestKit
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class PipelineSpec extends TestKit(ActorSystem("RequestContextSpecSys")) with FlatSpecLike with Matchers {

  import RequestContext._

  implicit val fm = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  val startTran = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      println("CalScope in startTran: ")
      ctx.copy()
    }
  }
  val syncException = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      throw new RuntimeException("BadMan")
    }
  }
  val syncIn1 = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      val name: String = "TestTran"
      ctx.withAttributes("syncIn1" -> name)
    }
  }
  val asyncIn1 = new AsyncHandler {
    override def handle(ctx: RequestContext): Future[RequestContext] = {
      Future.successful(ctx.addRequestHeaders(RawHeader("asyncIn1", "TestTran")))
    }
  }
  val syncOut1 = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ attributes2Headers(ctx.attributes) :+ RawHeader("syncOut1", "CalContext")))
      ctx.copy(response = newResp)
    }
  }
  val asyncOut1 = new AsyncHandler {
    override def handle(ctx: RequestContext): Future[RequestContext] = {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ ctx.request.headers :+ RawHeader("asyncOut1", "TestTran")))
      Future {
        ctx.copy(response = newResp)
      }
    }
  }
  val masterAction: HttpRequest => Future[HttpResponse] =
    req => {
      val hs = scala.collection.immutable.Seq(RawHeader("masterAction", "TestTran"))
      Future.successful(HttpResponse(entity = "HelloWorld", headers = hs))
    }
  val masterFlow: Flow[RequestContext, RequestContext, Unit] =
    Flow[RequestContext].mapAsync(1) {
      ctx => masterAction(ctx.request).map(resp => ctx.copy(response = Option(resp)))
    }

  "Simple flow" should "work" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, asyncIn1, syncIn1), Seq(asyncOut1, syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest())

    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "syncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "asyncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "masterAction").get.value() should be("TestTran")
    result.headers.find(_.name == "syncOut1").get.value() should be("CalContext")
    result.headers.find(_.name == "asyncOut1").get.value() should be("TestTran")
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }

  "Simple flow without future" should "work" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, syncIn1), Seq(syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest())

    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "syncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "masterAction").get.value() should be("TestTran")
    result.headers.find(_.name == "syncOut1").get.value() should be("CalContext")
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }

  "Simple flow with exception" should "bypass all subsequent handlers" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, asyncIn1, syncException, syncIn1), Seq(asyncOut1, syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest())

    val result = Await.result(resp, 5 seconds)
    result.status should be(StatusCodes.InternalServerError)
    result.headers.find(_.name == "asyncIn1") should be(None)
    result.headers.find(_.name == "syncIn1") should be(None)
    result.headers.find(_.name == "masterAction") should be(None)
    result.headers.find(_.name == "syncOut1") should be(None)
    result.headers.find(_.name == "asyncOut1") should be(None)
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("BadMan")

  }
}
