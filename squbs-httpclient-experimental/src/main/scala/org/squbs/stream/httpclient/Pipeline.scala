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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait Handler

trait SyncHandler extends Handler {
  def handle(ctx: RequestContext): RequestContext
}

object DefaultSyncHandler extends SyncHandler {
  override def handle(ctx: RequestContext): RequestContext = ctx
}

trait AsyncHandler extends Handler {
  def handle(ctx: RequestContext): Future[RequestContext]
}

case class PipelineSetting(inbound: Seq[Handler], outbound: Seq[Handler])

case class Pipeline(setting: PipelineSetting,
                    master: Flow[RequestContext, RequestContext, Unit],
                    preInbound: SyncHandler = DefaultSyncHandler,
                    postOutbound: SyncHandler = DefaultSyncHandler)(implicit exec: ExecutionContext, mat: Materializer) {


  val inbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.inbound)
  val outbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.outbound)
  val masterFlow: Flow[RequestContext, RequestContext, Unit] = {
    Flow[RequestContext].mapAsync(1) {
      ctx => ctx.error match {
        case None =>
          val result: Future[RequestContext] = Source.single(ctx).via(master).runWith(Sink.head)
          result
        case Some(t) => Future.successful(ctx.copy(response = Option(HttpResponse(500, entity = t.error.getMessage))))
      }
    }
  }
  val compositeSink: Sink[RequestContext, Future[HttpResponse]] =
    inbound
      .via(masterFlow)
      .via(outbound)
      .map(_.response.getOrElse(HttpResponse(404, entity = "Unknown resource!")))
      .toMat(Sink.head)(Keep.right)

  def run(request: HttpRequest): Future[HttpResponse] = {
    Source.single(RequestContext(request)).runWith(compositeSink)
  }

  private def genFlow(handlers: Seq[Handler]) = {
    Flow[RequestContext].mapAsync(1) {
      ctx => process(Left(Success(ctx)), handlers) match {
        case Left(rc) => Future.fromTry(rc)
        case Right(frc) => frc
      }
    }
  }

  private def process(ctx: Either[Try[RequestContext], Future[RequestContext]],
                      rest: Seq[Handler]): Either[Try[RequestContext], Future[RequestContext]] = {
    val newCtx = rest.size match {
      case 0 => ctx
      case _ => (rest(0), ctx) match {
        case (h: SyncHandler, Left(c)) => Left(c.map(handleSync(_, h)))
        case (h: SyncHandler, Right(fc)) => Right(fc.map(handleSync(_, h)))
        case (h: AsyncHandler, Left(c)) => Right(Future.fromTry(c).flatMap(handleAsync(_, h)))
        case (h: AsyncHandler, Right(fc)) => Right(fc.flatMap(handleAsync(_, h)))
      }
    }
    if (rest.size > 1) process(newCtx, rest.drop(1))
    else newCtx
  }

  private def handleAsync(rc: RequestContext, h: AsyncHandler): Future[RequestContext] = {
    rc.error match {
      case None => Try(h.handle(rc)) match {
        case Success(result) => result
        case Failure(t) => Future.successful(rc.copy(error = Option(ErrorLog(t))))
      }
      case _ => Future.successful(rc)
    }
  }

  private def handleSync(rc: RequestContext, h: SyncHandler): RequestContext = {

    rc.error match {
      case None => Try(h.handle(rc)) match {
        case Success(result) => result
        case Failure(t) => rc.copy(error = Option(ErrorLog(t)))
      }
      case _ => rc
    }
  }
}
